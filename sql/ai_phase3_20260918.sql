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
'请把较早的会话历史压缩成可继续工作的 Agent Checkpoint。必须保留：当前主题和最终目标；用户已确认决策；已完成工作和关键结果；当前进度；未完成任务和下一步；未解决问题；模型/页面/Agent 状态；已执行 Tool 与关键结果；已发生且不可逆的 WRITE；pending Tool/WRITE/确认状态；用户最新 Steering/纠偏；继续工作所需关键 ID、字段、配置、文件和接口；事实与暂定方案的区分；压缩后需要重新检查的事项。明确列出不要重复已经完成的工作。不要写普通聊天摘要。',
'请把较早的会话历史压缩成可继续工作的 Agent Checkpoint。必须保留：当前主题和最终目标；用户已确认决策；已完成工作和关键结果；当前进度；未完成任务和下一步；未解决问题；模型/页面/Agent 状态；已执行 Tool 与关键结果；已发生且不可逆的 WRITE；pending Tool/WRITE/确认状态；用户最新 Steering/纠偏；继续工作所需关键 ID、字段、配置、文件和接口；事实与暂定方案的区分；压缩后需要重新检查的事项。明确列出不要重复已经完成的工作。不要写普通聊天摘要。',
1, '0', 'system', sysdate()
where not exists (select 1 from ai_prompt where prompt_type='COMPACTION');

insert into ai_page_config(route, page_name, enabled, create_by, create_time)
select '/system/user', '用户管理', '0', 'system', sysdate()
where not exists (select 1 from ai_page_config where route='/system/user');
