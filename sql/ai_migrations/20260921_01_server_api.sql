-- 前置：20260920_01_phase35_p0_protocol.sql。
-- 用途：真实接口治理、服务端调用事实、按运行披露及结果来源授权。
-- 可重入：只建缺失表和菜单，不覆盖既有开放策略；原会话无需回填。
-- 回退：先关闭新能力并停止活动运行，再回退配对代码；保留表中审计事实，不自动删除。

create table if not exists ai_api_policy (
  capability_id varchar(64) not null,
  contract_hash char(64) not null,
  enabled tinyint not null default 0,
  revision bigint not null default 1,
  update_by varchar(64) not null,
  update_time datetime not null,
  primary key (capability_id)
) engine=innodb comment='AI 接口专属开放策略';

create table if not exists ai_server_call (
  call_id varchar(64) not null,
  conversation_id bigint not null,
  run_id bigint not null,
  user_id bigint not null,
  tool_name varchar(128) not null,
  capability_id varchar(64) default null,
  authorization_hash char(64) default null,
  risk_level varchar(32) not null,
  status varchar(32) not null,
  dedupe_key char(64) default null,
  result_id varchar(64) default null,
  result_json mediumtext default null,
  tool_result_json mediumtext default null,
  error_code varchar(64) default null,
  create_time datetime not null,
  start_time datetime default null,
  end_time datetime default null,
  expire_time datetime not null,
  primary key (call_id),
  unique key uk_ai_server_dedupe (dedupe_key),
  unique key uk_ai_server_result (result_id),
  key idx_ai_server_conversation (conversation_id, run_id),
  key idx_ai_server_expiry (user_id, expire_time)
) engine=innodb comment='AI 服务端调用与有期限结果';

create table if not exists ai_api_loaded (
  run_id bigint not null,
  capability_id varchar(64) not null,
  contract_hash char(64) not null,
  load_time datetime not null,
  primary key (run_id, capability_id)
) engine=innodb comment='AI 按运行加载的接口定义';

create table if not exists ai_server_result_guard (
  guard_id tinyint not null,
  primary key (guard_id)
) engine=innodb comment='AI 结果配额事务锁';
insert ignore into ai_server_result_guard values(1);

create table if not exists ai_business_source (
  conversation_id bigint not null,
  capability_id varchar(64) not null,
  authorization_hash char(64) not null,
  primary key (conversation_id, capability_id, authorization_hash)
) engine=innodb comment='AI 历史及派生成果的业务授权来源';

insert ignore into sys_menu values(125,'后端接口',2000,7,'apis','ai/apis/index','','',1,0,'C','0','0','ai:api:view','tree-table','admin',sysdate(),'',null,'从真实接口发现并逐项治理');
insert ignore into sys_menu values(2110,'后端接口查看',125,1,'#','','','',1,0,'F','0','0','ai:api:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2111,'后端接口开放',125,2,'#','','','',1,0,'F','0','0','ai:api:edit','#','admin',sysdate(),'',null,'');
