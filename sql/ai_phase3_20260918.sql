-- RuoYi AI Assistant phase-three migration (apply to an existing phase-two database)
alter table ai_model
  add column context_window_tokens int not null default 65536 comment 'Agent 工作上下文预算' after default_reasoning_effort,
  add column auto_compaction char(1) not null default '0' comment '0开启自动压缩 1关闭' after context_window_tokens,
  add column compaction_threshold_percent int not null default 75 comment '自动压缩阈值百分比' after auto_compaction;
alter table ai_message add column run_id bigint(20) default null after reasoning_effort;
alter table ai_pending_tool_call
  add column run_id bigint(20) default null after reasoning_effort,
  add column route varchar(255) default '' after run_id,
  add column page_instance_id varchar(64) default null after route,
  add column page_version bigint(20) default null after page_instance_id;
-- New phase-three tables and seed data are defined in sql/ai_20260918.sql for fresh installs.
