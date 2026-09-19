-- predecessor: P0-before-Phase-3.5 AI baseline, including ai_hardening_b1_b3_20260919.sql
-- purpose: persist Capability Protocol v1 + pageId in pending frontend Tool Call snapshots.
-- safe rerun: yes. DDL is guarded through information_schema and legacy PENDING invalidation is status-guarded.
-- legacy data: PENDING rows without a complete v1 snapshot become EXPIRED; resolved/cancelled/superseded history is unchanged.
-- rollback: only before new v1 pending rows are created; dropping these columns after v1 traffic would discard security-relevant facts.

set @ai_schema = database();

set @has_capability_protocol = (
  select count(*) from information_schema.columns
  where table_schema=@ai_schema and table_name='ai_pending_tool_call' and column_name='capability_protocol'
);
set @ddl_capability_protocol = if(
  @has_capability_protocol=0,
  'alter table ai_pending_tool_call add column capability_protocol varchar(64) default null after run_id',
  'select 1'
);
prepare ai_stmt from @ddl_capability_protocol;
execute ai_stmt;
deallocate prepare ai_stmt;

set @has_page_id = (
  select count(*) from information_schema.columns
  where table_schema=@ai_schema and table_name='ai_pending_tool_call' and column_name='page_id'
);
set @ddl_page_id = if(
  @has_page_id=0,
  'alter table ai_pending_tool_call add column page_id varchar(128) default null after capability_protocol',
  'select 1'
);
prepare ai_stmt from @ddl_page_id;
execute ai_stmt;
deallocate prepare ai_stmt;

update ai_pending_tool_call
set status='EXPIRED', resolved_time=coalesce(resolved_time, sysdate())
where status='PENDING'
  and (capability_protocol is null or capability_protocol<>'ruoyi-semantic-page-v1'
       or page_id is null or page_id=''
       or route is null or route=''
       or page_instance_id is null or page_instance_id=''
       or page_version is null);
