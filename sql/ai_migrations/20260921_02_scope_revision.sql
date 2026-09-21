-- 当前若依行范围依赖的归属事实发生变化时，使旧 AI 结果失效。
create table if not exists ai_scope_revision (
  guard_id tinyint not null primary key,
  revision bigint not null default 0
) engine=innodb comment='数据归属变化版本';
insert ignore into ai_scope_revision values(1,0);
create table if not exists ai_scope_trigger_manifest (
  trigger_name varchar(64) not null primary key,
  table_name varchar(64) not null,
  event_name varchar(16) not null,
  action_hash char(64) not null
) engine=innodb comment='归属触发器安装完整性记录';
-- 安装期间关闭披露；完成后提升版本，作废保护缺失期间可能过时的结果。
delete from ai_scope_trigger_manifest;

drop trigger if exists ai_scope_user_update;
create trigger ai_scope_user_update after update on sys_user for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1
    and (not(old.dept_id <=> new.dept_id) or not(old.del_flag <=> new.del_flag));
drop trigger if exists ai_scope_user_delete;
create trigger ai_scope_user_delete after delete on sys_user for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1;
drop trigger if exists ai_scope_dept_update;
create trigger ai_scope_dept_update after update on sys_dept for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1
    and (not(old.parent_id <=> new.parent_id) or not(old.ancestors <=> new.ancestors)
      or not(old.del_flag <=> new.del_flag));
drop trigger if exists ai_scope_dept_delete;
create trigger ai_scope_dept_delete after delete on sys_dept for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1;
drop trigger if exists ai_scope_user_role_delete;
create trigger ai_scope_user_role_delete after delete on sys_user_role for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1;
drop trigger if exists ai_scope_user_role_update;
create trigger ai_scope_user_role_update after update on sys_user_role for each row
  update ai_scope_revision set revision=revision+1 where guard_id=1
    and (not(old.user_id <=> new.user_id) or not(old.role_id <=> new.role_id));

update ai_scope_revision set revision=revision+1 where guard_id=1;
insert into ai_scope_trigger_manifest(trigger_name,table_name,event_name,action_hash)
select trigger_name,event_object_table,event_manipulation,sha2(action_statement,256)
from information_schema.triggers where trigger_schema=database()
  and trigger_name in ('ai_scope_user_update','ai_scope_user_delete','ai_scope_dept_update',
    'ai_scope_dept_delete','ai_scope_user_role_delete','ai_scope_user_role_update')
on duplicate key update table_name=values(table_name),event_name=values(event_name),action_hash=values(action_hash);
