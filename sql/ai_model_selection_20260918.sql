-- AI model selection migration.
-- Apply ONCE to databases created before explicit model selection was introduced.

alter table ai_model
  add column selected char(1) not null default '0' comment '0已加入系统 1已移除' after display_name;

-- Earlier versions bulk-imported every /models result as disabled + unknown.
-- Hide untouched bulk-imported rows while preserving models that were actually enabled,
-- made default, capability-tested, or given a reasoning default.
update ai_model
set selected = '1'
where enabled = '1'
  and default_model = '1'
  and tool_capability = 'UNKNOWN'
  and reasoning_capability = 'UNKNOWN'
  and default_reasoning_effort is null;
