create table if not exists ai_workspace_policy (
  policy_id tinyint not null primary key,
  enabled tinyint not null default 0,
  revision bigint not null default 1,
  update_by varchar(64) not null,
  update_time datetime not null
) engine=innodb comment='AI 隔离工作空间开放策略';
insert ignore into ai_workspace_policy values(1,0,1,'admin',sysdate());

create table if not exists ai_artifact (
  artifact_id char(32) not null primary key,
  user_id bigint not null,
  conversation_id bigint not null,
  run_id bigint not null,
  file_name varchar(100) not null,
  file_bytes bigint not null,
  sha256 char(64) not null,
  expire_time bigint not null comment '到期时刻，UTC 毫秒',
  create_time datetime not null,
  key idx_ai_artifact_conversation(conversation_id),
  key idx_ai_artifact_expire(expire_time)
) engine=innodb comment='AI 私有交付成果归属与保留期';

insert ignore into sys_menu values(127,'工作空间',2000,9,'workspace','ai/workspace/index','','',1,0,'C','0','0','ai:workspace:view','server','admin',sysdate(),'',null,'隔离命令与成果空间治理');
insert ignore into sys_menu values(2114,'工作空间治理查看',127,1,'#','','','',1,0,'F','0','0','ai:workspace:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2115,'工作空间治理修改',127,2,'#','','','',1,0,'F','0','0','ai:workspace:edit','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2116,'工作空间使用',127,3,'#','','','',1,0,'F','0','0','ai:workspace:use','#','admin',sysdate(),'',null,'使用权限不包含治理权限');
