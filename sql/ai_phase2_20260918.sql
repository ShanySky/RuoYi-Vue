-- RuoYi AI Assistant phase-two migration
-- Apply ONCE to databases that already initialized sql/ai_20260918.sql before phase two.
-- Fresh installations only need the latest sql/ai_20260918.sql and should not run this migration.

alter table ai_model
  add column reasoning_capability varchar(16) not null default 'UNKNOWN' comment 'UNKNOWN/SUPPORTED/UNSUPPORTED' after tool_capability,
  add column reasoning_efforts varchar(128) default null comment '已验证支持的思考档位，逗号分隔' after reasoning_capability,
  add column default_reasoning_effort varchar(16) default null comment '默认思考档位，null 表示 Provider 默认' after reasoning_efforts;

alter table ai_conversation
  add column reasoning_effort varchar(16) default null after model_id;

alter table ai_message
  add column model_id bigint(20) default null after tool_arguments,
  add column model_code varchar(191) default null after model_id,
  add column reasoning_effort varchar(16) default null after model_code;

alter table ai_pending_tool_call
  add column model_id bigint(20) default null after risk_level,
  add column model_code varchar(191) default null after model_id,
  add column reasoning_effort varchar(16) default null after model_code;

-- Existing phase-one pending calls cannot be resumed safely with per-turn model semantics.
-- Mark only unresolved legacy calls as expired; resolved history remains untouched.
update ai_pending_tool_call
set status = 'EXPIRED'
where status = 'PENDING'
  and model_id is null;
