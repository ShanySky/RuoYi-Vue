create table if not exists ai_data_policy (
  scope_key varchar(64) not null primary key,
  contract_hash char(64) not null,
  enabled tinyint not null default 0,
  fields_json text not null,
  operations_json varchar(128) not null,
  revision bigint not null default 1,
  update_by varchar(64) not null,
  update_time datetime not null
) engine=innodb comment='AI 数据库及表字段操作开放策略';

insert ignore into sys_menu values(126,'数据查询',2000,8,'data','ai/data/index','','',1,0,'C','0','0','ai:data:view','table','admin',sysdate(),'',null,'在原业务授权内治理查询分析');
insert ignore into sys_menu values(2112,'数据查询治理查看',126,1,'#','','','',1,0,'F','0','0','ai:data:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2113,'数据查询治理修改',126,2,'#','','','',1,0,'F','0','0','ai:data:edit','#','admin',sysdate(),'',null,'');
