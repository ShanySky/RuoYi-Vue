#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${AI_CI_DB_HOST:-127.0.0.1}"
DB_USER="${AI_CI_DB_USER:-root}"
DB_PASSWORD="${AI_CI_DB_PASSWORD:-password}"
FRESH_DB="${AI_CI_FRESH_DB:-ry_ai_fresh_acceptance}"
UPGRADE_DB="${AI_CI_UPGRADE_DB:-ry_ai_upgrade_acceptance}"

# 删除只允许落在明确命名的验收库，防止环境变量误指业务库。
[[ "$FRESH_DB" =~ ^ry_ai_[A-Za-z0-9_]*acceptance$ ]]
[[ "$UPGRADE_DB" =~ ^ry_ai_[A-Za-z0-9_]*acceptance$ ]]
[[ "$FRESH_DB" != "$UPGRADE_DB" ]]

mysql_server() {
  MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -u"$DB_USER" "$@"
}
mysql_db() {
  local db="$1"; shift
  MYSQL_PWD="$DB_PASSWORD" mysql -h"$DB_HOST" -u"$DB_USER" "$db" "$@"
}
cleanup() {
  mysql_server -e "drop database if exists \`$FRESH_DB\`; drop database if exists \`$UPGRADE_DB\`;" >/dev/null
}
trap cleanup EXIT

mysql_server -e "drop database if exists \`$FRESH_DB\`; drop database if exists \`$UPGRADE_DB\`; create database \`$FRESH_DB\` character set utf8mb4 collate utf8mb4_unicode_ci; create database \`$UPGRADE_DB\` character set utf8mb4 collate utf8mb4_unicode_ci;"

# Fresh install: base RuoYi + Quartz + exactly one current AI schema entry point.
mysql_db "$FRESH_DB" < sql/ry_20260417.sql
mysql_db "$FRESH_DB" < sql/quartz.sql
mysql_db "$FRESH_DB" < sql/ai_fresh_install.sql

# Upgrade: reproduce the last published baseline, preserve representative legacy rows,
# then execute the ordered Phase 3.5 migration twice to prove safe rerun behavior.
mysql_db "$UPGRADE_DB" < sql/ry_20260417.sql
mysql_db "$UPGRADE_DB" < sql/quartz.sql
mysql_db "$UPGRADE_DB" < sql/ai_20260918.sql
mysql_db "$UPGRADE_DB" < sql/ai_hardening_b1_b3_20260919.sql
mysql_db "$UPGRADE_DB" -e "
insert into ai_pending_tool_call(call_id,conversation_id,user_id,tool_name,arguments_json,risk_level,model_id,model_code,run_id,route,page_instance_id,page_version,status,create_time,expire_time)
values('legacy-pending',9001,1,'page_system_user_search','{}','READ',1,'legacy-model',9001,'/system/user','legacy:one',1,'PENDING',sysdate(),date_add(sysdate(),interval 10 minute));
insert into ai_pending_tool_call(call_id,conversation_id,user_id,tool_name,arguments_json,risk_level,model_id,model_code,run_id,route,page_instance_id,page_version,status,create_time,expire_time,resolved_time)
values('legacy-resolved',9002,1,'page_system_user_search','{}','READ',1,'legacy-model',9002,'/system/user','legacy:two',1,'RESOLVED',sysdate(),date_add(sysdate(),interval 10 minute),sysdate());
"
# 全部有序升级执行两次，覆盖新接口表并证明可重入。
for pass in 1 2; do
  for migration in sql/ai_migrations/*.sql; do
    mysql_db "$UPGRADE_DB" < "$migration"
  done
done

for db in "$FRESH_DB" "$UPGRADE_DB"; do
  test "$(mysql_server -Nse "select count(*) from information_schema.tables where table_schema='$db' and left(table_name,3)='ai_';")" -ge 11
  test "$(mysql_server -Nse "select count(*) from information_schema.columns where table_schema='$db' and table_name='ai_pending_tool_call' and column_name in ('capability_protocol','page_id');")" = 2
  test "$(mysql_server -Nse "select count(*) from information_schema.statistics where table_schema='$db' and table_name='ai_pending_tool_call' and index_name in ('uk_ai_pending_conversation_call','idx_ai_pending_conversation');")" -ge 4
  test "$(mysql_db "$db" -Nse "select count(*) from ai_prompt where prompt_type in ('SYSTEM','COMPACTION') and enabled='0';")" = 2
  test "$(mysql_db "$db" -Nse "select count(*) from ai_prompt_version where prompt_type in ('SYSTEM','COMPACTION');")" -ge 2
  test "$(mysql_db "$db" -Nse "select count(*) from ai_page_config where route='/system/user' and enabled='0';")" = 1
  test "$(mysql_db "$db" -Nse "select count(*) from ai_server_result_guard where guard_id=1;")" = 1
  test "$(mysql_db "$db" -Nse "select count(*) from ai_api_policy where enabled=1;")" = 0
  test "$(mysql_db "$db" -Nse "select count(*) from sys_menu where perms in ('ai:api:view','ai:api:edit');")" = 3
  test "$(mysql_db "$db" -Nse "select count(*) from ai_scope_revision where guard_id=1;")" = 1
  test "$(mysql_db "$db" -Nse "select count(*) from ai_scope_trigger_manifest m join information_schema.triggers t on t.trigger_schema=database() and t.trigger_name=m.trigger_name and t.event_object_table=m.table_name and t.event_manipulation=m.event_name and t.action_timing='AFTER' and sha2(t.action_statement,256)=m.action_hash;")" = 6
  # 归属版本随原事务回滚，提交后才使旧授权失效。
  before_scope=$(mysql_db "$db" -Nse "select revision from ai_scope_revision where guard_id=1;")
  mysql_db "$db" -e "start transaction; update sys_user set dept_id=104 where user_id=2; rollback;"
  test "$(mysql_db "$db" -Nse "select revision from ai_scope_revision where guard_id=1;")" = "$before_scope"
  mysql_db "$db" -e "update sys_user set dept_id=104 where user_id=2;"
  test "$(mysql_db "$db" -Nse "select revision from ai_scope_revision where guard_id=1;")" -gt "$before_scope"
done

test "$(mysql_db "$UPGRADE_DB" -Nse "select status from ai_pending_tool_call where call_id='legacy-pending';")" = "EXPIRED"
test "$(mysql_db "$UPGRADE_DB" -Nse "select status from ai_pending_tool_call where call_id='legacy-resolved';")" = "RESOLVED"

mysql_server -Nse "
select table_name,column_name,column_type,is_nullable,coalesce(column_default,'<NULL>'),column_key,extra
from information_schema.columns
where table_schema='$FRESH_DB' and left(table_name,3)='ai_'
order by table_name,ordinal_position;" > /tmp/ai-fresh-columns.txt
mysql_server -Nse "
select table_name,column_name,column_type,is_nullable,coalesce(column_default,'<NULL>'),column_key,extra
from information_schema.columns
where table_schema='$UPGRADE_DB' and left(table_name,3)='ai_'
order by table_name,ordinal_position;" > /tmp/ai-upgrade-columns.txt
diff -u /tmp/ai-fresh-columns.txt /tmp/ai-upgrade-columns.txt

mysql_server -Nse "
select table_name,index_name,non_unique,seq_in_index,column_name
from information_schema.statistics
where table_schema='$FRESH_DB' and left(table_name,3)='ai_'
order by table_name,index_name,seq_in_index;" > /tmp/ai-fresh-indexes.txt
mysql_server -Nse "
select table_name,index_name,non_unique,seq_in_index,column_name
from information_schema.statistics
where table_schema='$UPGRADE_DB' and left(table_name,3)='ai_'
order by table_name,index_name,seq_in_index;" > /tmp/ai-upgrade-indexes.txt
diff -u /tmp/ai-fresh-indexes.txt /tmp/ai-upgrade-indexes.txt


# Workflow schema-entry guards: ephemeral fresh databases must use the canonical
# current schema instead of composing historical baselines without migrations.
# Historical SQL names are still expected above in this script for upgrade-path testing.
grep -q "backend/sql/ai_fresh_install.sql" .github/workflows/ai-agent-public-preview.yml
! grep -q "backend/sql/ai_20260918.sql" .github/workflows/ai-agent-public-preview.yml
! grep -q "backend/sql/ai_hardening_b1_b3_20260919.sql" .github/workflows/ai-agent-public-preview.yml
grep -q "sql/ai_fresh_install.sql" .github/workflows/ai-agent-real-gpt.yml
! grep -q "sql/ai_20260918.sql" .github/workflows/ai-agent-real-gpt.yml

echo "AI_SCHEMA_ACCEPTANCE_OK"
