#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${AI_CI_BASE_URL:-http://127.0.0.1:8080}"
DB_HOST="${AI_CI_DB_HOST:-127.0.0.1}"
DB_USER="${AI_CI_DB_USER:-root}"
DB_PASSWORD="${AI_CI_DB_PASSWORD:-password}"
DB_NAME="${AI_CI_DB_NAME:-ry-vue}"
LOGIN_USER="${AI_CI_LOGIN_USER:-admin}"
LOGIN_PASSWORD="${AI_CI_LOGIN_PASSWORD:-admin123}"

mysql_ci() {
  mysql -h"$DB_HOST" -u"$DB_USER" -p"$DB_PASSWORD" -Nse "$1" "$DB_NAME"
}

# B1: Spring AI implementation details stay inside the adapter.
! grep -q "org.springframework.ai" ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiAgentLoopService.java
! grep -q "org.springframework.ai" ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiContextService.java
! grep -q "org.springframework.ai" ruoyi-ai/src/main/java/com/ruoyi/ai/service/AiRunService.java
grep -q "implements AgentRuntime" ruoyi-ai/src/main/java/com/ruoyi/ai/runtime/SpringAiAgentRuntime.java

# B2: business services do not call Run state SQL primitives directly.
! grep -R "runs\.transition\|runs\.supersedeFrom" -n ruoyi-ai/src/main/java/com/ruoyi/ai/service | grep -v RunLifecycleService.java

LOGIN=$(curl -fsS -H 'Content-Type: application/json'   -d "{\"username\":\"$LOGIN_USER\",\"password\":\"$LOGIN_PASSWORD\"}" "$BASE_URL/login")
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
AUTH_HEADER="Authorization: Bearer $TOKEN"
MODEL_ID=$(mysql_ci "select model_id from ai_model where enabled='0' order by default_model asc, model_id limit 1")

test "$(mysql_ci "select count(*) from ai_prompt_version where prompt_type in ('SYSTEM','COMPACTION')")" -ge "2"
OLD_VERSION=$(mysql_ci "select version_no from ai_prompt where prompt_type='SYSTEM'")
DEFAULT_VERSION=$(mysql_ci "select version_no from ai_prompt_version where prompt_type='SYSTEM' and content=(select default_content from ai_prompt where prompt_type='SYSTEM') order by version_no desc limit 1")

BEFORE=$(curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"B3_BEFORE_PROMPT\",\"route\":\"/index\",\"pageContext\":{},\"frontendTools\":[]}"   "$BASE_URL/ai/chat/turn")
BEFORE_RUN=$(printf '%s' "$BEFORE" | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["type"]=="MESSAGE"; print(d["runId"])')
test "$(mysql_ci "select system_prompt_version from ai_run where run_id=$BEFORE_RUN")" = "$OLD_VERSION"

CUSTOM_BODY='B3_CUSTOM_SYSTEM_PROMPT {{currentUser}}'
UPDATED=$(curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json' -X PUT   -d '{"content":"B3_CUSTOM_SYSTEM_PROMPT {{currentUser}}","enabled":true}'   "$BASE_URL/ai/config/prompts/SYSTEM")
NEW_VERSION=$(printf '%s' "$UPDATED" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]==200; print(d["data"]["versionNo"])')
test "$NEW_VERSION" -eq "$((OLD_VERSION + 1))"
test "$(mysql_ci "select count(*) from ai_prompt_version where prompt_type='SYSTEM' and version_no=$OLD_VERSION")" = "1"
test "$(mysql_ci "select count(*) from ai_prompt_version where prompt_type='SYSTEM' and version_no=$NEW_VERSION and content='$CUSTOM_BODY'")" = "1"

CUSTOM=$(curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"B3_CUSTOM_PROMPT_RUN\",\"route\":\"/index\",\"pageContext\":{},\"frontendTools\":[]}"   "$BASE_URL/ai/chat/turn")
CUSTOM_CONV=$(printf '%s' "$CUSTOM" | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["type"]=="MESSAGE"; print(d["conversationId"])')
CUSTOM_RUN=$(printf '%s' "$CUSTOM" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["runId"])')
test "$(mysql_ci "select system_prompt_version from ai_run where run_id=$CUSTOM_RUN")" = "$NEW_VERSION"

VERSION_ROWS_BEFORE=$(mysql_ci "select count(*) from ai_prompt_version where prompt_type='SYSTEM'")
curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json' -X PUT -d '{"enabled":false}'   "$BASE_URL/ai/config/prompts/SYSTEM" >/tmp/b3-disable.json
test "$(mysql_ci "select version_no from ai_prompt where prompt_type='SYSTEM'")" = "$NEW_VERSION"
test "$(mysql_ci "select count(*) from ai_prompt_version where prompt_type='SYSTEM'")" = "$VERSION_ROWS_BEFORE"

DISABLED=$(curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"B3_DISABLED_USES_DEFAULT\",\"route\":\"/index\",\"pageContext\":{},\"frontendTools\":[]}"   "$BASE_URL/ai/chat/turn")
DISABLED_RUN=$(printf '%s' "$DISABLED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["runId"])')
test "$(mysql_ci "select system_prompt_version from ai_run where run_id=$DISABLED_RUN")" = "$DEFAULT_VERSION"

RESTORED=$(curl -fsS -H "$AUTH_HEADER" -X POST "$BASE_URL/ai/config/prompts/SYSTEM/restore-default")
RESTORED_VERSION=$(printf '%s' "$RESTORED" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]==200; print(d["data"]["versionNo"])')
test "$RESTORED_VERSION" -eq "$((NEW_VERSION + 1))"
test "$(mysql_ci "select count(*) from ai_prompt_version where prompt_type='SYSTEM' and version_no=$RESTORED_VERSION and content=(select default_content from ai_prompt where prompt_type='SYSTEM')")" = "1"

AFTER=$(curl -fsS -H "$AUTH_HEADER" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"B3_AFTER_RESTORE\",\"route\":\"/index\",\"pageContext\":{},\"frontendTools\":[]}"   "$BASE_URL/ai/chat/turn")
AFTER_RUN=$(printf '%s' "$AFTER" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["runId"])')
test "$(mysql_ci "select system_prompt_version from ai_run where run_id=$AFTER_RUN")" = "$RESTORED_VERSION"
test "$(mysql_ci "select system_prompt_version from ai_run where run_id=$CUSTOM_RUN")" = "$NEW_VERSION"

curl -fsS -H "$AUTH_HEADER" "$BASE_URL/ai/admin/audit/$CUSTOM_CONV" >/tmp/b3-audit.json
python3 - "$NEW_VERSION" <<'PY'
import json, sys
version = int(sys.argv[1])
with open('/tmp/b3-audit.json', encoding='utf-8') as f:
    data = json.load(f)
assert data['code'] == 200
history = data['data']['promptVersions']
assert any(v['promptType'] == 'SYSTEM' and v['versionNo'] == version
           and v['content'].startswith('B3_CUSTOM_SYSTEM_PROMPT') for v in history)
PY

echo "B1_B2_B3_HARDENING_ACCEPTANCE_OK"
