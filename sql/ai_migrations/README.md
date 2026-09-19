# AI ordered migrations

This directory is the ordered upgrade path for existing AI-enabled RuoYi databases.

Rules:

- New files use `YYYYMMDD_NN_topic.sql`; lexical order is execution order.
- `sql/ai_fresh_install.sql` is the only current AI schema entry point for new environments.
- Historical scripts in `sql/` remain frozen as legacy baselines and are not renamed or rewritten.
- Every migration documents its predecessor, purpose, rerun behavior, legacy-data handling, and rollback conditions.
- After a migration is merged into `chatgpt/ai-agent-assistant`, do not rewrite its structural meaning. Add a later migration instead.
- No Flyway/Liquibase or dynamic migration runner is introduced in Phase 3.5.

Current predecessor for the first ordered migration is the repository's P0-before-Phase-3.5 AI baseline:
`ai_20260918.sql` plus the historical phase/model/B1-B3 incremental scripts that an existing environment already requires.
