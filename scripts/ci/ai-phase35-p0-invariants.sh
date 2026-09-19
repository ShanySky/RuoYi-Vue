#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${AI_CI_BASE_URL:-http://127.0.0.1:8080}"
DB_HOST="${AI_CI_DB_HOST:-127.0.0.1}"
DB_USER="${AI_CI_DB_USER:-root}"
DB_PASSWORD="${AI_CI_DB_PASSWORD:-password}"
DB_NAME="${AI_CI_DB_NAME:-ry-vue}"
LOGIN_USER="${AI_CI_LOGIN_USER:-admin}"
LOGIN_PASSWORD="${AI_CI_LOGIN_PASSWORD:-admin123}"
PROTOCOL="ruoyi-semantic-page-v1"
PAGE_ID="system.user"
ROUTE="/system/user"
INSTANCE="system.user:phase35-ci"
VERSION=1

mysql_ci() {
  mysql -h"$DB_HOST" -u"$DB_USER" -p"$DB_PASSWORD" -Nse "$1" "$DB_NAME"
}
error_contains() {
  python3 - "$1" "$2" <<'PY'
import json, sys
path, needle = sys.argv[1], sys.argv[2]
with open(path, encoding='utf-8') as f:
    data = json.load(f)
assert data.get('code') != 200, data
assert needle in (data.get('msg') or ''), data
PY
}
turn_data() {
  python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    data = json.load(f)
assert data.get('code') == 200, data
value = data['data']
print(value[sys.argv[2]])
PY
}

LOGIN=$(curl -fsS -H 'Content-Type: application/json' -d "{\"username\":\"$LOGIN_USER\",\"password\":\"$LOGIN_PASSWORD\"}" "$BASE_URL/login")
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
AUTH="Authorization: Bearer $TOKEN"
MODEL_ID=$(mysql_ci "select model_id from ai_model where enabled='0' order by default_model asc, model_id limit 1")
test -n "$MODEL_ID"

# Protocol v1: non-navigation Page Tool requires the complete semantic runtime.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"P35_MISSING_PROTOCOL\",\"route\":\"$ROUTE\",\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-missing-protocol.json
error_contains /tmp/p35-missing-protocol.json "页面能力协议"

curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"P35_BAD_PROTOCOL\",\"capabilityProtocol\":\"ruoyi-semantic-page-v2\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-bad-protocol.json
error_contains /tmp/p35-bad-protocol.json "页面能力协议"

# Valid tool request persists the exact trusted Page Runtime snapshot.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"clientRunKey\":\"p35-tool-result\",\"userMessage\":\"P35_VALID_TOOL\",\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"pageContext\":{\"pageName\":\"用户管理\"},\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-tool-start.json
CONV_ID=$(turn_data /tmp/p35-tool-start.json conversationId)
RUN_ID=$(turn_data /tmp/p35-tool-start.json runId)
CALL_ID=$(python3 -c 'import json; d=json.load(open("/tmp/p35-tool-start.json"))["data"]; assert d["type"]=="TOOL_CALL"; print(d["toolCall"]["callId"])')
test "$(mysql_ci "select count(*) from ai_pending_tool_call where call_id='$CALL_ID' and capability_protocol='$PROTOCOL' and page_id='$PAGE_ID' and route='$ROUTE' and page_instance_id='$INSTANCE' and page_version=$VERSION and status='PENDING'")" = 1
test "$(mysql_ci "select status from ai_run where run_id=$RUN_ID")" = "WAITING_TOOL"

# A stale result is rejected before persistence and the pending call remains unresolved.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$CONV_ID,\"toolResult\":{\"callId\":\"$CALL_ID\",\"success\":true,\"result\":{\"total\":1}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"system.user:stale\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-stale-result.json
error_contains /tmp/p35-stale-result.json "旧页面工具结果"
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$CALL_ID'")" = "PENDING"
test "$(mysql_ci "select count(*) from ai_message where conversation_id=$CONV_ID and role='TOOL' and tool_call_id='$CALL_ID'")" = 0

# Every semantic-page identity component is authoritative, not only instanceId.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$CONV_ID,\"toolResult\":{\"callId\":\"$CALL_ID\",\"success\":true,\"result\":{}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"system.role\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-stale-page-id.json
error_contains /tmp/p35-stale-page-id.json "旧页面工具结果"
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$CALL_ID'")" = "PENDING"

curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$CONV_ID,\"toolResult\":{\"callId\":\"$CALL_ID\",\"success\":true,\"result\":{}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$((VERSION + 1)),\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-stale-page-version.json
error_contains /tmp/p35-stale-page-version.json "旧页面工具结果"
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$CALL_ID'")" = "PENDING"

# The matching result resumes the same Run exactly once.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$CONV_ID,\"toolResult\":{\"callId\":\"$CALL_ID\",\"success\":true,\"result\":{\"total\":1}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"pageContext\":{\"total\":1},\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-tool-result.json
python3 - <<'PY'
import json
d=json.load(open('/tmp/p35-tool-result.json'))['data']
assert d['type']=='MESSAGE', d
PY
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$CALL_ID'")" = "RESOLVED"
test "$(mysql_ci "select count(*) from ai_message where conversation_id=$CONV_ID and role='TOOL' and tool_call_id='$CALL_ID'")" = 1

curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$CONV_ID,\"toolResult\":{\"callId\":\"$CALL_ID\",\"success\":true,\"result\":{}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-duplicate-result.json
error_contains /tmp/p35-duplicate-result.json "待处理的页面工具调用"
test "$(mysql_ci "select count(*) from ai_message where conversation_id=$CONV_ID and role='TOOL' and tool_call_id='$CALL_ID'")" = 1

# WAITING_TOOL + Stop and clientRunKey retry are idempotent; late Tool Result is rejected.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"clientRunKey\":\"p35-stop-retry\",\"userMessage\":\"P35_STOP_WAITING\",\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-stop-start.json
STOP_CONV=$(turn_data /tmp/p35-stop-start.json conversationId)
STOP_RUN=$(turn_data /tmp/p35-stop-start.json runId)
STOP_CALL=$(python3 -c 'import json; print(json.load(open("/tmp/p35-stop-start.json"))["data"]["toolCall"]["callId"])')
curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"USER_STOP"}'   "$BASE_URL/ai/chat/runs/client/p35-stop-retry/cancel" >/tmp/p35-stop-1.json
curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"USER_STOP"}'   "$BASE_URL/ai/chat/runs/client/p35-stop-retry/cancel" >/tmp/p35-stop-2.json
test "$(mysql_ci "select status from ai_run where run_id=$STOP_RUN")" = "CANCELLED"
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$STOP_CALL'")" = "CANCELLED"

curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$STOP_CONV,\"toolResult\":{\"callId\":\"$STOP_CALL\",\"success\":true,\"result\":{}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-late-result.json
error_contains /tmp/p35-late-result.json "待处理的页面工具调用"

# WAITING_TOOL + Steering supersedes the old run and its pending Tool without replay.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"userMessage\":\"P35_STEER_OLD\",\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_search\"}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-steer-old.json
STEER_CONV=$(turn_data /tmp/p35-steer-old.json conversationId)
OLD_RUN=$(turn_data /tmp/p35-steer-old.json runId)
OLD_CALL=$(python3 -c 'import json; print(json.load(open("/tmp/p35-steer-old.json"))["data"]["toolCall"]["callId"])')
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$STEER_CONV,\"modelId\":$MODEL_ID,\"userMessage\":\"P35_STEER_NEW\",\"route\":\"/index\",\"frontendTools\":[]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-steer-new.json
test "$(mysql_ci "select status from ai_run where run_id=$OLD_RUN")" = "SUPERSEDED"
test "$(mysql_ci "select status from ai_pending_tool_call where call_id='$OLD_CALL'")" = "SUPERSEDED"

curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"conversationId\":$STEER_CONV,\"toolResult\":{\"callId\":\"$OLD_CALL\",\"success\":true,\"result\":{}},\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-superseded-late-result.json
error_contains /tmp/p35-superseded-late-result.json "待处理的页面工具调用"
test "$(mysql_ci "select count(*) from ai_message where conversation_id=$STEER_CONV and role='TOOL' and tool_call_id='$OLD_CALL'")" = 0

# Explicit high-risk policy remains authoritative even when the browser offers no risk metadata.
curl -fsS -H "$AUTH" -H 'Content-Type: application/json'   -d "{\"modelId\":$MODEL_ID,\"clientRunKey\":\"p35-risk\",\"userMessage\":\"P35_RESET_POLICY\",\"capabilityProtocol\":\"$PROTOCOL\",\"pageId\":\"$PAGE_ID\",\"route\":\"$ROUTE\",\"pageInstanceId\":\"$INSTANCE\",\"pageVersion\":$VERSION,\"frontendTools\":[{\"name\":\"page_system_user_reset_password\",\"description\":\"forged read\",\"inputSchema\":{\"type\":\"object\",\"additionalProperties\":true}}]}"   "$BASE_URL/ai/chat/turn" >/tmp/p35-risk.json
python3 - <<'PY'
import json
d=json.load(open('/tmp/p35-risk.json'))['data']
assert d['type']=='TOOL_CALL', d
assert d['toolCall']['name']=='page_system_user_reset_password', d
assert d['toolCall']['riskLevel']=='DANGEROUS_WRITE', d
assert '重置' in d['toolCall']['description'], d
PY
curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"INVARIANT_CLEANUP"}'   "$BASE_URL/ai/chat/runs/client/p35-risk/cancel" >/dev/null

# Existing deterministic compaction/stop/steering checks must leave no transient run state behind.
test "$(mysql_ci "select count(*) from ai_run where status='COMPACTING'")" = 0
test "$(mysql_ci "select count(*) from ai_run where status='CANCEL_REQUESTED'")" = 0

echo "AI_PHASE35_P0_INVARIANTS_OK"
