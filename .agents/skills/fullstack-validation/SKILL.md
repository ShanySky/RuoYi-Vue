---
name: fullstack-validation
description: Use when a RuoYi AI change needs real backend, frontend, database, Redis, Agent/Tool Loop, E2E, or temporary public-preview validation. Choose the smallest validation depth that can prove the changed behavior; do not treat build success alone as completion.
---

# Fullstack Validation

## Purpose

Validate RuoYi AI changes with real execution instead of relying only on static inspection.

Canonical workflow files currently include:

- Backend: `.github/workflows/ai-agent-fullstack-e2e.yml`
- Backend: `.github/workflows/ai-agent-public-preview.yml`
- Frontend: `.github/workflows/ai-agent-frontend-ci.yml`
- Browser E2E: `ShanySky/RuoYi-Vue3/tests/ai-agent-e2e.mjs`

Always inspect the current target branch versions before copying commands from this Skill. Repository reality wins if the workflow has changed.

## 1. Choose validation depth first

Do not mechanically start the whole stack for every change.

### Documentation / rule-only change

Normally verify:

- Files are on the intended branch.
- Links / referenced paths exist.
- AGENTS / Rule / Skill routing is internally consistent.
- No unintended code/config changes were introduced.

No full runtime is required unless the documentation change itself alters CI / executable scripts.

### Backend-only change

At minimum where relevant:

1. Maven build / tests.
2. Start Spring Boot.
3. Call a representative real HTTP endpoint.
4. If MySQL / Redis / AI runtime behavior changed, verify those dependencies are actually used.

### Frontend-only change

At minimum where relevant:

1. `npm install`.
2. `npm run build:prod`.
3. For UI / interaction behavior, start Vite and exercise the page rather than stopping at build success.
4. For API-related behavior, verify `/dev-api` against a real backend.

### Cross-repo / Agent / Tool change

Prefer full closed-loop validation:

```text
MySQL
→ Redis
→ initialize SQL
→ build backend
→ start backend
→ backend smoke request
→ install/start frontend
→ frontend + /dev-api smoke
→ Agent / Tool Loop or browser E2E
→ persistence / state assertions
→ optional public preview
```

## 2. Preflight

Before running anything:

1. Confirm backend and frontend target branches.
2. Record both commit SHAs used for acceptance.
3. Re-read current `pom.xml`, frontend `package.json`, relevant SQL and workflow files.
4. Check whether the task modifies one or both repositories.
5. Check whether a workflow pins the other repository to a different branch.

### Important paired-branch rule

The current backend `ai-agent-fullstack-e2e.yml` explicitly checks out frontend:

`chatgpt/ai-agent-assistant`

Therefore a green backend workflow does **not** automatically prove a frontend feature branch was tested.

For cross-repo work, make sure acceptance uses the intended backend/frontend pair. Prefer one of:

- run the stack with both target branches checked out locally / in the same runner;
- or adjust the working-branch workflow so its frontend checkout explicitly points to the intended paired branch.

Record the actual SHAs. Never infer branch pairing from a green badge.

## 3. Current baseline commands

Current verified baseline is Java 17 / Spring Boot 4.1.x / Node 20 / MySQL 8 / Redis 7.4.

### Database initialization

Current fullstack workflow imports:

```bash
mysql -h127.0.0.1 -uroot -ppassword ry-vue < sql/ry_20260417.sql
mysql -h127.0.0.1 -uroot -ppassword ry-vue < sql/quartz.sql
mysql -h127.0.0.1 -uroot -ppassword ry-vue < sql/ai_20260918.sql
mysql -h127.0.0.1 -uroot -ppassword ry-vue < sql/ai_hardening_b1_b3_20260919.sql
```

Before using this list, inspect the current branch for newer migration / fresh-install rules. Do not assume these four files remain complete forever.

### Backend build

```bash
mvn -B -DskipTests clean package
```

If the change has relevant automated tests, run them instead of using `-DskipTests` as the only verification.

### Backend runtime directories

Current RuoYi logging may require:

```bash
sudo mkdir -p /home/ruoyi/logs /home/ruoyi/uploadPath
sudo chown -R "$USER":"$USER" /home/ruoyi
```

A successful build does not prove the JAR can start.

### Backend start and smoke

```bash
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

Then make a real request, for example:

```text
GET http://127.0.0.1:8080/captchaImage
```

If the process exits, read the real backend log before classifying the failure.

### Frontend install / build

```bash
npm install
npm run build:prod
```

### Frontend runtime smoke

```bash
npm run dev -- --host 127.0.0.1 --port 5173
```

Verify both:

```text
GET http://127.0.0.1:5173/
GET http://127.0.0.1:5173/dev-api/captchaImage
```

The second request proves the frontend dev proxy reaches the backend.

## 4. AI Agent / Tool Loop acceptance

For AI changes, do not stop after generic RuoYi smoke tests.

Validate the behaviors affected by the change, for example:

- Provider / Model configuration can be read and saved.
- A real chat turn completes.
- Conversation / Message / Run state persists correctly.
- Only registered and allowed tools are exposed.
- Frontend Tool Result returns to the same valid Agent Run.
- Stop prevents old Run results from resuming execution.
- Steering supersedes the old Run without inventing rollback.
- WRITE confirmation and permission boundaries hold.
- Cross-page navigation invalidates stale page instances.
- Checkpoint / Compaction preserves source history and current execution semantics.
- Page actions invoke real RuoYi handlers / APIs instead of bypassing business controls.

Use the existing deterministic mock Provider E2E when it proves the code path. Use Real Provider validation only when the changed behavior actually depends on real model / provider semantics.

## 5. Browser E2E

The current fullstack workflow installs Playwright separately and runs:

```bash
node tests/ai-agent-e2e.mjs
```

Current acceptance also checks generated visual evidence and database assertions.

When the task changes UI or interaction behavior:

- run browser-level acceptance where practical;
- inspect blocking browser errors;
- verify the actual interaction, not only screenshot generation.

For purely backend or documentation changes, do not run browser E2E merely for ceremony.

## 6. Temporary public preview

Use public preview only when human visual / interaction acceptance adds value.

Current pattern:

```text
browser
  ↓ HTTPS
Cloudflare Quick Tunnel
  ↓
Vite
  ↓ /dev-api
Spring Boot
  ↓
MySQL + Redis
```

Public preview is for temporary acceptance, not production hosting.

Do not commit permanent security relaxations just to make a temporary tunnel work.

## 7. Troubleshooting rules

### Spring Boot builds but exits immediately

Check runtime logs and required filesystem paths such as `/home/ruoyi/logs`.

Build success != runtime success.

### Runner has no `redis-cli`

Do not assume host tools exist. A reusable check is:

```bash
docker run --rm --network host redis:7.4 redis-cli -h 127.0.0.1 ping
```

### Quick Tunnel returns 403

Check Vite Host validation. If a temporary external host allowance is required, keep it limited to CI / preview context rather than weakening normal production configuration.

### Quick Tunnel hostname initially fails to resolve

Allow a retry window. A newly issued `trycloudflare.com` hostname may not resolve immediately.

### npm reports dependency vulnerabilities

Do not run destructive:

```bash
npm audit fix --force
```

merely to make the audit output green. Dependency/security work should be evaluated as its own compatibility change.

## 8. Failure handling

When validation fails:

1. Capture the real log / response.
2. Classify the failure: code, environment, configuration, external dependency, or branch-pair mismatch.
3. Fix what is in scope.
4. Re-run the failed proof.
5. Do not report “verified” for a path that was not actually exercised.

## 9. Completion report

Report facts, not generic confidence:

- backend SHA / branch used;
- frontend SHA / branch used;
- what was changed;
- what was actually run;
- which tests / requests / browser flows passed;
- whether MySQL / Redis were really involved;
- whether backend and frontend were actually started;
- whether Agent / Tool Loop was exercised;
- whether a public preview was created;
- remaining limitations or unverified paths.

The final question is not “did the build pass?” but “did the executed evidence prove the user-visible / architectural behavior that changed?”
