-- RuoYi AI 3.5 B1/B2/B3 hardening incremental SQL.
-- B10 long-term migration conventions are intentionally out of scope.

create table if not exists ai_prompt_version (
  prompt_version_id bigint(20)  not null auto_increment,
  prompt_type       varchar(32) not null,
  version_no        int         not null,
  content           mediumtext  not null,
  create_by         varchar(64) default '',
  create_time       datetime    default null,
  primary key (prompt_version_id),
  unique key uk_ai_prompt_version_type_no (prompt_type, version_no),
  key idx_ai_prompt_version_type_time (prompt_type, create_time)
) engine=innodb comment='AI Prompt 历史版本';

-- Seed the currently effective stored versions first. Pre-hardening versions whose
-- contents were already overwritten cannot be reconstructed and are not invented.
insert ignore into ai_prompt_version(prompt_type, version_no, content, create_by, create_time)
select prompt_type, version_no, content, coalesce(nullif(update_by,''), create_by, 'system'),
       coalesce(update_time, create_time, sysdate())
from ai_prompt;

-- The repository still knows the original default bodies for these baseline
-- versions. Seed them only when that version number is not already occupied.
insert ignore into ai_prompt_version(prompt_type, version_no, content, create_by, create_time)
select prompt_type,
       case when prompt_type='SYSTEM' then 1 when prompt_type='COMPACTION' then 2 else version_no end,
       default_content, 'system', coalesce(create_time, sysdate())
from ai_prompt
where prompt_type in ('SYSTEM','COMPACTION');
