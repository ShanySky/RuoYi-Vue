-- RuoYi AI Assistant phase-one schema

create table if not exists ai_provider (
  provider_id       bigint(20)      not null auto_increment    comment 'Provider ID',
  name              varchar(64)     not null                   comment 'Provider 名称',
  provider_type     varchar(32)     not null default 'OPENAI_COMPATIBLE' comment 'Provider 类型',
  base_url          varchar(512)    not null                   comment 'OpenAI-compatible Base URL',
  token_cipher      text            default null               comment 'AES/GCM 加密后的 Token',
  enabled           char(1)         not null default '1'       comment '状态（0启用 1停用）',
  timeout_seconds   int             not null default 30        comment '请求超时秒数',
  create_by         varchar(64)     default '',
  create_time       datetime        default null,
  update_by         varchar(64)     default '',
  update_time       datetime        default null,
  remark            varchar(500)    default null,
  primary key (provider_id)
) engine=innodb comment='AI Provider 配置';

create table if not exists ai_model (
  model_id          bigint(20)      not null auto_increment,
  provider_id       bigint(20)      not null,
  model_code        varchar(191)    not null,
  display_name      varchar(191)    not null,
  enabled           char(1)         not null default '1'       comment '0启用 1停用',
  default_model     char(1)         not null default '1'       comment '0默认 1普通',
  tool_capability   varchar(16)     not null default 'UNKNOWN' comment 'UNKNOWN/SUPPORTED/UNSUPPORTED',
  last_sync_time    datetime        default null,
  create_by         varchar(64)     default '',
  create_time       datetime        default null,
  update_by         varchar(64)     default '',
  update_time       datetime        default null,
  remark            varchar(500)    default null,
  primary key (model_id),
  unique key uk_ai_model_provider_code (provider_id, model_code),
  key idx_ai_model_enabled (enabled, default_model)
) engine=innodb comment='AI 模型目录';

create table if not exists ai_conversation (
  conversation_id  bigint(20)      not null auto_increment,
  user_id           bigint(20)      not null,
  model_id          bigint(20)      default null,
  title             varchar(200)    default '',
  route             varchar(255)    default '',
  status            varchar(20)     not null default 'ACTIVE',
  create_time       datetime        default null,
  update_time       datetime        default null,
  primary key (conversation_id),
  key idx_ai_conversation_user (user_id, update_time)
) engine=innodb comment='AI 会话';

create table if not exists ai_message (
  message_id        bigint(20)      not null auto_increment,
  conversation_id  bigint(20)      not null,
  sequence_no       int             not null,
  role              varchar(20)     not null,
  content           mediumtext      default null,
  tool_call_id      varchar(128)    default null,
  tool_name         varchar(128)    default null,
  tool_arguments    mediumtext      default null,
  create_time       datetime        default null,
  primary key (message_id),
  unique key uk_ai_message_sequence (conversation_id, sequence_no),
  key idx_ai_message_conversation (conversation_id)
) engine=innodb comment='AI 会话消息';

create table if not exists ai_pending_tool_call (
  pending_id        bigint(20)      not null auto_increment,
  call_id           varchar(128)    not null,
  conversation_id  bigint(20)      not null,
  user_id           bigint(20)      not null,
  tool_name         varchar(128)    not null,
  arguments_json    mediumtext      default null,
  risk_level        varchar(32)     not null,
  status            varchar(20)     not null default 'PENDING',
  create_time       datetime        default null,
  expire_time       datetime        default null,
  resolved_time     datetime        default null,
  primary key (pending_id),
  unique key uk_ai_pending_call_id (call_id),
  key idx_ai_pending_conversation (conversation_id, status)
) engine=innodb comment='AI 前端工具待执行调用';

-- AI 配置菜单及权限。使用独立 SQL，避免修改 RuoYi 基础初始化文件。
insert into sys_menu(menu_id, menu_name, parent_id, order_num, path, component, route_name, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
select 118, 'AI 配置', 1, 10, 'aiConfig', 'ai/config/index', '', '', 1, 0, 'C', '0', '0', 'ai:config:view', 'skill', 'admin', sysdate(), '', null, 'AI Provider 与模型配置'
where not exists (select 1 from sys_menu where menu_id=118);

insert into sys_menu(menu_id, menu_name, parent_id, order_num, path, component, route_name, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
select 1061, 'AI 配置查看', 118, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'ai:config:view', '#', 'admin', sysdate(), '', null, ''
where not exists (select 1 from sys_menu where menu_id=1061);

insert into sys_menu(menu_id, menu_name, parent_id, order_num, path, component, route_name, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
select 1062, 'AI 配置修改', 118, 2, '#', '', '', '', 1, 0, 'F', '0', '0', 'ai:config:edit', '#', 'admin', sysdate(), '', null, ''
where not exists (select 1 from sys_menu where menu_id=1062);
