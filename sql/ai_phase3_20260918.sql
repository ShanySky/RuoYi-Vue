-- RuoYi AI Assistant phase-three migration
-- Apply once to a database that already contains the phase-two AI tables.

alter table ai_model
  add column context_window_tokens int not null default 65536 comment 'Agent 工作上下文预算' after default_reasoning_effort,
  add column auto_compaction char(1) not null default '0' comment '0开启自动压缩 1关闭' after context_window_tokens,
  add column compaction_threshold_percent int not null default 75 comment '自动压缩阈值百分比' after auto_compaction;

alter table ai_message
  add column run_id bigint(20) default null after reasoning_effort;

alter table ai_pending_tool_call
  add column run_id bigint(20) default null after reasoning_effort,
  add column route varchar(255) default '' after run_id,
  add column page_instance_id varchar(64) default null after route,
  add column page_version bigint(20) default null after page_instance_id;

create table if not exists ai_run (
  run_id                    bigint(20)   not null auto_increment,
  client_run_key            varchar(64)  not null,
  conversation_id           bigint(20)   not null,
  user_id                   bigint(20)   not null,
  model_id                  bigint(20)   not null,
  model_code                varchar(191) not null,
  reasoning_effort          varchar(16)  default null,
  status                    varchar(24)  not null default 'RUNNING',
  cancel_reason             varchar(255) default null,
  superseded_by_run_id      bigint(20)   default null,
  system_prompt_version     int          default null,
  compaction_prompt_version int          default null,
  input_tokens              bigint(20)   not null default 0,
  cache_read_tokens         bigint(20)   not null default 0,
  cache_write_tokens        bigint(20)   not null default 0,
  total_tokens              bigint(20)   not null default 0,
  create_time               datetime     default null,
  update_time               datetime     default null,
  end_time                  datetime     default null,
  primary key (run_id),
  unique key uk_ai_run_client_key (client_run_key),
  key idx_ai_run_conversation_status (conversation_id, status),
  key idx_ai_run_user_time (user_id, create_time)
) engine=innodb comment='AI Agent Run';

create table if not exists ai_checkpoint (
  checkpoint_id       bigint(20)   not null auto_increment,
  conversation_id     bigint(20)   not null,
  run_id              bigint(20)   default null,
  covered_sequence_no int          not null default 0,
  summary              mediumtext   not null,
  model_id             bigint(20)   default null,
  model_code           varchar(191) default null,
  estimated_tokens     int          not null default 0,
  status               varchar(20)  not null default 'ACTIVE',
  create_time          datetime     default null,
  primary key (checkpoint_id),
  key idx_ai_checkpoint_conversation (conversation_id, checkpoint_id)
) engine=innodb comment='AI 会话压缩 Checkpoint';

create table if not exists ai_prompt (
  prompt_id       bigint(20)   not null auto_increment,
  prompt_type     varchar(32)  not null,
  content         mediumtext   not null,
  default_content mediumtext   not null,
  version_no      int          not null default 1,
  enabled         char(1)      not null default '0' comment '0启用 1停用',
  create_by       varchar(64)  default '',
  create_time     datetime     default null,
  update_by       varchar(64)  default '',
  update_time     datetime     default null,
  primary key (prompt_id),
  unique key uk_ai_prompt_type (prompt_type)
) engine=innodb comment='AI 系统 Prompt 配置';

create table if not exists ai_user_preference (
  user_id                         bigint(20)  not null,
  default_model_id                bigint(20)  default null,
  default_reasoning_effort        varchar(16) default null,
  chat_font_size                  varchar(16) not null default 'standard',
  send_shortcut                   varchar(20) not null default 'enter',
  double_esc_enabled              char(1)     not null default '0',
  history_entry_visible           char(1)     not null default '1',
  auto_restore_last_conversation  char(1)     not null default '1',
  assistant_open_mode             varchar(24) not null default 'last',
  create_time                     datetime    default null,
  update_time                     datetime    default null,
  primary key (user_id)
) engine=innodb comment='AI 用户偏好';

create table if not exists ai_page_config (
  page_id       bigint(20)   not null auto_increment,
  route         varchar(255) not null,
  page_name     varchar(128) not null,
  enabled       char(1)      not null default '0',
  create_by     varchar(64)  default '',
  create_time   datetime     default null,
  update_by     varchar(64)  default '',
  update_time   datetime     default null,
  primary key (page_id),
  unique key uk_ai_page_route (route)
) engine=innodb comment='AI 页面接入配置';

insert into ai_prompt(prompt_type, content, default_content, version_no, enabled, create_by, create_time)
select 'SYSTEM',
'你是 RuoYi 管理系统内的 AI 助手。你可以正常对话，也可以使用系统提供的语义化页面能力完成用户任务。\n\n必须遵守：\n1. 只能使用本次请求实际提供的能力，不能假装点击、查询、修改或保存。\n2. 页面能力执行前不要声称已经完成；只有收到对应 Tool Result 后才能确认结果。\n3. 优先使用语义化页面能力，不描述 DOM、选择器、坐标或 Playwright。\n4. 不虚构页面记录、ID 或执行结果；信息不足时读取页面上下文或继续调用工具。\n5. WRITE/高风险操作必须服从宿主系统确认流程，不能绕过。\n6. 你的权限不超过当前登录用户；工具未提供通常表示页面不支持、未接入或用户没有权限。\n7. 用户管理中 userName 是登录账号，现有用户的登录账号不能通过当前编辑流程修改；nickName 才是可修改的用户昵称。\n8. 当任务需要跨页面时，可使用系统提供的导航能力；页面切换后必须使用新页面实例提供的能力。',
'你是 RuoYi 管理系统内的 AI 助手。你可以正常对话，也可以使用系统提供的语义化页面能力完成用户任务。\n\n必须遵守：\n1. 只能使用本次请求实际提供的能力，不能假装点击、查询、修改或保存。\n2. 页面能力执行前不要声称已经完成；只有收到对应 Tool Result 后才能确认结果。\n3. 优先使用语义化页面能力，不描述 DOM、选择器、坐标或 Playwright。\n4. 不虚构页面记录、ID 或执行结果；信息不足时读取页面上下文或继续调用工具。\n5. WRITE/高风险操作必须服从宿主系统确认流程，不能绕过。\n6. 你的权限不超过当前登录用户；工具未提供通常表示页面不支持、未接入或用户没有权限。\n7. 用户管理中 userName 是登录账号，现有用户的登录账号不能通过当前编辑流程修改；nickName 才是可修改的用户昵称。\n8. 当任务需要跨页面时，可使用系统提供的导航能力；页面切换后必须使用新页面实例提供的能力。',
1, '0', 'system', sysdate()
where not exists (select 1 from ai_prompt where prompt_type='SYSTEM');

insert into ai_prompt(prompt_type, content, default_content, version_no, enabled, create_by, create_time)
select 'COMPACTION',
'请把较早的会话历史压缩成可继续工作的 Agent Checkpoint，而不是普通聊天摘要。严格按以下固定结构输出，并保留没有内容的栏目：\n## 任务主线\n- 当前主题：\n- 最终目标：\n## 用户决策与最新指令\n- 已确认决策：\n- 最新 Steering/纠偏：\n## 已完成工作与真实结果\n- 已完成工作：\n- 关键结果：\n- 已发生且不可逆的 WRITE：\n- 明确禁止重复的已完成事项：\n## 当前运行状态\n- 当前进度/正在做什么：\n- 模型/Reasoning：\n- 页面/路由/页面实例：\n- Agent/Run 状态：\n## Tool 与确认状态\n- 已执行 Tool 与关键结果：\n- pending Tool/WRITE/确认：\n- 已取消/未完成 Tool（不得当作已执行）：\n## 待办与下一步\n- 未完成任务：\n- 未解决问题：\n- 下一步：\n## 继续工作所需关键引用\n- ID/字段/配置/文件/接口：\n## 事实、暂定方案与复核项\n- 已验证事实：\n- 暂定方案：\n- 压缩后需要重新检查的事项：\n必须保持事实与暂定方案的区分；不得把未完成或已取消的 Tool/WRITE 写成成功；不得省略最新用户指令；明确列出不要重复已经完成的工作。',
'请把较早的会话历史压缩成可继续工作的 Agent Checkpoint，而不是普通聊天摘要。严格按以下固定结构输出，并保留没有内容的栏目：\n## 任务主线\n- 当前主题：\n- 最终目标：\n## 用户决策与最新指令\n- 已确认决策：\n- 最新 Steering/纠偏：\n## 已完成工作与真实结果\n- 已完成工作：\n- 关键结果：\n- 已发生且不可逆的 WRITE：\n- 明确禁止重复的已完成事项：\n## 当前运行状态\n- 当前进度/正在做什么：\n- 模型/Reasoning：\n- 页面/路由/页面实例：\n- Agent/Run 状态：\n## Tool 与确认状态\n- 已执行 Tool 与关键结果：\n- pending Tool/WRITE/确认：\n- 已取消/未完成 Tool（不得当作已执行）：\n## 待办与下一步\n- 未完成任务：\n- 未解决问题：\n- 下一步：\n## 继续工作所需关键引用\n- ID/字段/配置/文件/接口：\n## 事实、暂定方案与复核项\n- 已验证事实：\n- 暂定方案：\n- 压缩后需要重新检查的事项：\n必须保持事实与暂定方案的区分；不得把未完成或已取消的 Tool/WRITE 写成成功；不得省略最新用户指令；明确列出不要重复已经完成的工作。',
2, '0', 'system', sysdate()
where not exists (select 1 from ai_prompt where prompt_type='COMPACTION');

-- H/F: 系统页面能力白名单；0=启用。动态 ID 路由使用 *。
insert into ai_page_config(route,page_name,enabled,create_by,create_time) values
('/system/user','用户管理','0','system',sysdate()),
('/system/user-auth/role/*','分配角色','0','system',sysdate()),
('/system/role','角色管理','0','system',sysdate()),
('/system/role-auth/user/*','角色分配用户','0','system',sysdate()),
('/system/menu','菜单管理','0','system',sysdate()),
('/system/dept','部门管理','0','system',sysdate()),
('/system/post','岗位管理','0','system',sysdate()),
('/system/dict','字典类型','0','system',sysdate()),
('/system/dict-data/index/*','字典数据','0','system',sysdate()),
('/system/notice','通知公告','0','system',sysdate()),
('/user/profile','个人中心','0','system',sysdate()),
('/monitor/online','在线用户','0','system',sysdate()),
('/monitor/job','定时任务','0','system',sysdate()),
('/monitor/job-log/index/*','调度日志','0','system',sysdate()),
('/monitor/logininfor','登录日志','0','system',sysdate()),
('/monitor/operlog','操作日志','0','system',sysdate()),
('/monitor/cache','缓存监控','0','system',sysdate()),
('/monitor/cacheList','缓存列表','0','system',sysdate()),
('/monitor/server','服务监控','0','system',sysdate()),
('/monitor/druid','数据监控','0','system',sysdate()),
('/tool/gen','代码生成','0','system',sysdate()),
('/tool/gen-edit/index/*','修改生成配置','0','system',sysdate()),
('/tool/build','表单构建','0','system',sysdate()),
('/tool/swagger','系统接口','0','system',sysdate())
on duplicate key update page_name=values(page_name);

-- H: 会话策略复用 RuoYi 系统参数。
insert into sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
select 'AI 会话保留天数','ai.conversation.retentionDays','90','Y','system',sysdate(),'超过保留期且无活动 Run 的会话由 AI 清理任务物理清理'
where not exists(select 1 from sys_config where config_key='ai.conversation.retentionDays');
insert into sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
select 'AI 用户允许归档会话','ai.conversation.userArchiveEnabled','true','Y','system',sysdate(),'用户是否可将自己的会话归档'
where not exists(select 1 from sys_config where config_key='ai.conversation.userArchiveEnabled');
insert into sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
select 'AI 用户允许删除会话','ai.conversation.userDeleteEnabled','false','Y','system',sysdate(),'用户删除为软删除；到保留期后由系统物理清理'
where not exists(select 1 from sys_config where config_key='ai.conversation.userDeleteEnabled');

-- H: 从第二阶段“系统管理 / AI 配置”迁移到一级“AI 管理”，并保留隐藏兼容路由。
insert ignore into sys_menu values(2000,'AI 管理',0,4,'ai',null,'','',1,0,'M','0','0','','skill','admin',sysdate(),'',null,'AI 系统管理目录');
update sys_menu set menu_name='AI 服务',parent_id=2000,order_num=1,path='service',component='ai/service/index',perms='ai:config:view',remark='AI Provider 配置' where menu_id=118;
insert ignore into sys_menu values(119,'模型管理',2000,2,'models','ai/models/index','','',1,0,'C','0','0','ai:config:view','list','admin',sysdate(),'',null,'AI 系统模型管理');
insert ignore into sys_menu values(120,'提示词管理',2000,3,'prompts','ai/prompts/index','','',1,0,'C','0','0','ai:prompt:view','edit','admin',sysdate(),'',null,'System/Compaction Prompt 管理');
insert ignore into sys_menu values(121,'页面能力',2000,4,'pages','ai/pages/index','','',1,0,'C','0','0','ai:page:view','tree-table','admin',sysdate(),'',null,'AI 页面接入能力管理');
insert ignore into sys_menu values(122,'会话策略',2000,5,'policy','ai/policy/index','','',1,0,'C','0','0','ai:policy:view','time-range','admin',sysdate(),'',null,'AI 会话保留与用户操作策略');
insert ignore into sys_menu values(123,'会话审计',2000,6,'audit','ai/audit/index','','',1,0,'C','0','0','ai:conversation:audit','form','admin',sysdate(),'',null,'只读 AI 会话审计');
insert ignore into sys_menu values(124,'AI 配置兼容入口',1,99,'aiConfig','ai/config/index','','',1,0,'C','1','0','ai:config:view','#','admin',sysdate(),'',null,'兼容第二阶段 /system/aiConfig 路由，不在菜单显示');
update sys_menu set parent_id=118 where menu_id in (1061,1062);
insert ignore into sys_menu values(2101,'Prompt 查看',120,1,'#','','','',1,0,'F','0','0','ai:prompt:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2102,'Prompt 修改',120,2,'#','','','',1,0,'F','0','0','ai:prompt:edit','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2103,'页面能力查看',121,1,'#','','','',1,0,'F','0','0','ai:page:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2104,'页面能力修改',121,2,'#','','','',1,0,'F','0','0','ai:page:edit','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2105,'会话策略查看',122,1,'#','','','',1,0,'F','0','0','ai:policy:view','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2106,'会话策略修改',122,2,'#','','','',1,0,'F','0','0','ai:policy:edit','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2107,'会话审计查看',123,1,'#','','','',1,0,'F','0','0','ai:conversation:audit','#','admin',sysdate(),'',null,'');
insert ignore into sys_menu values(2108,'模型配置修改',119,1,'#','','','',1,0,'F','0','0','ai:config:edit','#','admin',sysdate(),'',null,'');
