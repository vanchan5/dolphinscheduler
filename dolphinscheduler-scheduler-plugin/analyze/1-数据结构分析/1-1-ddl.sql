-- ============================================
-- Quartz 定时器相关表结构 DDL
-- 版本: 3.3.0
-- 说明: 从 docs/sql/3-3-0.sql 中提取的 Quartz 相关表结构
-- ============================================

-- ============================================
-- 1. 作业详情表 (Job Details)
-- ============================================
create table if not exists qrtz_job_details
(
    sched_name        varchar(120) not null,
    job_name          varchar(200) not null,
    job_group         varchar(200) not null,
    description       varchar(250),
    job_class_name    varchar(250) not null,
    is_durable        boolean      not null,
    is_nonconcurrent  boolean      not null,
    is_update_data    boolean      not null,
    requests_recovery boolean      not null,
    job_data          bytea,
    primary key (sched_name, job_name, job_group)
);

comment on table qrtz_job_details is 'Quartz作业详情表，存储所有作业的定义信息';
comment on column qrtz_job_details.sched_name is '调度器名称';
comment on column qrtz_job_details.job_name is '作业名称';
comment on column qrtz_job_details.job_group is '作业组名';
comment on column qrtz_job_details.description is '作业描述';
comment on column qrtz_job_details.job_class_name is '作业实现类的全限定名';
comment on column qrtz_job_details.is_durable is '是否持久化，true表示作业在调度器关闭后仍然存在';
comment on column qrtz_job_details.is_nonconcurrent is '是否非并发执行，true表示同一作业实例不能并发执行';
comment on column qrtz_job_details.is_update_data is '是否更新数据';
comment on column qrtz_job_details.requests_recovery is '是否请求恢复，true表示作业失败后需要恢复';
comment on column qrtz_job_details.job_data is '作业数据，存储序列化的作业参数';

create index if not exists idx_qrtz_j_req_recovery
    on qrtz_job_details (sched_name, requests_recovery);

create index if not exists idx_qrtz_j_grp
    on qrtz_job_details (sched_name, job_group);

-- ============================================
-- 2. 触发器表 (Triggers)
-- ============================================
create table if not exists qrtz_triggers
(
    sched_name     varchar(120) not null,
    trigger_name   varchar(200) not null,
    trigger_group  varchar(200) not null,
    job_name       varchar(200) not null,
    job_group      varchar(200) not null,
    description    varchar(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority       integer,
    trigger_state  varchar(16)  not null,
    trigger_type   varchar(8)   not null,
    start_time     bigint       not null,
    end_time       bigint,
    calendar_name  varchar(200),
    misfire_instr  smallint,
    job_data       bytea,
    primary key (sched_name, trigger_name, trigger_group)
);

comment on table qrtz_triggers is 'Quartz触发器表，存储所有触发器的定义信息，与作业详情表关联';
comment on column qrtz_triggers.sched_name is '调度器名称';
comment on column qrtz_triggers.trigger_name is '触发器名称';
comment on column qrtz_triggers.trigger_group is '触发器组名';
comment on column qrtz_triggers.job_name is '关联的作业名称';
comment on column qrtz_triggers.job_group is '关联的作业组名';
comment on column qrtz_triggers.description is '触发器描述';
comment on column qrtz_triggers.next_fire_time is '下次触发时间（时间戳，毫秒）';
comment on column qrtz_triggers.prev_fire_time is '上次触发时间（时间戳，毫秒）';
comment on column qrtz_triggers.priority is '触发器优先级，数值越大优先级越高';
comment on column qrtz_triggers.trigger_state is '触发器状态：WAITING, ACQUIRED, EXECUTING, COMPLETE, PAUSED, BLOCKED, ERROR';
comment on column qrtz_triggers.trigger_type is '触发器类型：SIMPLE, CRON, BLOB, CAL_INT';
comment on column qrtz_triggers.start_time is '触发器开始时间（时间戳，毫秒）';
comment on column qrtz_triggers.end_time is '触发器结束时间（时间戳，毫秒），null表示无结束时间';
comment on column qrtz_triggers.calendar_name is '关联的日历名称，用于排除特定日期';
comment on column qrtz_triggers.misfire_instr is '错过触发时的处理策略：-1=忽略，0=立即触发，1=下次触发';
comment on column qrtz_triggers.job_data is '触发器数据，存储序列化的触发器参数';

create index if not exists idx_qrtz_t_j
    on qrtz_triggers (sched_name, job_name, job_group);

create index if not exists idx_qrtz_t_jg
    on qrtz_triggers (sched_name, job_group);

create index if not exists idx_qrtz_t_c
    on qrtz_triggers (sched_name, calendar_name);

create index if not exists idx_qrtz_t_g
    on qrtz_triggers (sched_name, trigger_group);

create index if not exists idx_qrtz_t_state
    on qrtz_triggers (sched_name, trigger_state);

create index if not exists idx_qrtz_t_n_state
    on qrtz_triggers (sched_name, trigger_name, trigger_group, trigger_state);

create index if not exists idx_qrtz_t_n_g_state
    on qrtz_triggers (sched_name, trigger_group, trigger_state);

create index if not exists idx_qrtz_t_next_fire_time
    on qrtz_triggers (sched_name, next_fire_time);

create index if not exists idx_qrtz_t_nft_st
    on qrtz_triggers (sched_name, trigger_state, next_fire_time);

create index if not exists idx_qrtz_t_nft_misfire
    on qrtz_triggers (sched_name, misfire_instr, next_fire_time);

create index if not exists idx_qrtz_t_nft_st_misfire
    on qrtz_triggers (sched_name, misfire_instr, next_fire_time, trigger_state);

create index if not exists idx_qrtz_t_nft_st_misfire_grp
    on qrtz_triggers (sched_name, misfire_instr, next_fire_time, trigger_group, trigger_state);

-- ============================================
-- 3. 简单触发器表 (Simple Triggers)
-- ============================================
create table if not exists qrtz_simple_triggers
(
    sched_name      varchar(120) not null,
    trigger_name    varchar(200) not null,
    trigger_group   varchar(200) not null,
    repeat_count    bigint       not null,
    repeat_interval bigint       not null,
    times_triggered bigint       not null,
    primary key (sched_name, trigger_name, trigger_group)
);

comment on table qrtz_simple_triggers is '简单触发器表，存储简单触发器的特定属性，用于固定间隔的重复触发';
comment on column qrtz_simple_triggers.sched_name is '调度器名称';
comment on column qrtz_simple_triggers.trigger_name is '触发器名称';
comment on column qrtz_simple_triggers.trigger_group is '触发器组名';
comment on column qrtz_simple_triggers.repeat_count is '重复次数，-1表示无限重复';
comment on column qrtz_simple_triggers.repeat_interval is '重复间隔（毫秒）';
comment on column qrtz_simple_triggers.times_triggered is '已触发次数';

-- ============================================
-- 4. Cron触发器表 (Cron Triggers)
-- ============================================
create table if not exists qrtz_cron_triggers
(
    sched_name      varchar(120) not null,
    trigger_name    varchar(200) not null,
    trigger_group   varchar(200) not null,
    cron_expression varchar(120) not null,
    time_zone_id    varchar(80),
    primary key (sched_name, trigger_name, trigger_group)
);

comment on table qrtz_cron_triggers is 'Cron触发器表，存储Cron触发器的特定属性，用于基于Cron表达式的复杂调度';
comment on column qrtz_cron_triggers.sched_name is '调度器名称';
comment on column qrtz_cron_triggers.trigger_name is '触发器名称';
comment on column qrtz_cron_triggers.trigger_group is '触发器组名';
comment on column qrtz_cron_triggers.cron_expression is 'Cron表达式，定义触发时间规则';
comment on column qrtz_cron_triggers.time_zone_id is '时区ID，如Asia/Shanghai';

-- ============================================
-- 5. 属性触发器表 (Simple Property Triggers)
-- ============================================
create table if not exists qrtz_simprop_triggers
(
    sched_name    varchar(120) not null,
    trigger_name  varchar(200) not null,
    trigger_group varchar(200) not null,
    str_prop_1    varchar(512),
    str_prop_2    varchar(512),
    str_prop_3    varchar(512),
    int_prop_1    integer,
    int_prop_2    integer,
    long_prop_1   bigint,
    long_prop_2   bigint,
    dec_prop_1    numeric(13, 4),
    dec_prop_2    numeric(13, 4),
    bool_prop_1   boolean,
    bool_prop_2   boolean,
    primary key (sched_name, trigger_name, trigger_group)
);

comment on table qrtz_simprop_triggers is '属性触发器表，存储带属性的简单触发器的扩展属性';
comment on column qrtz_simprop_triggers.sched_name is '调度器名称';
comment on column qrtz_simprop_triggers.trigger_name is '触发器名称';
comment on column qrtz_simprop_triggers.trigger_group is '触发器组名';
comment on column qrtz_simprop_triggers.str_prop_1 is '字符串属性1';
comment on column qrtz_simprop_triggers.str_prop_2 is '字符串属性2';
comment on column qrtz_simprop_triggers.str_prop_3 is '字符串属性3';
comment on column qrtz_simprop_triggers.int_prop_1 is '整数属性1';
comment on column qrtz_simprop_triggers.int_prop_2 is '整数属性2';
comment on column qrtz_simprop_triggers.long_prop_1 is '长整数属性1';
comment on column qrtz_simprop_triggers.long_prop_2 is '长整数属性2';
comment on column qrtz_simprop_triggers.dec_prop_1 is '小数属性1';
comment on column qrtz_simprop_triggers.dec_prop_2 is '小数属性2';
comment on column qrtz_simprop_triggers.bool_prop_1 is '布尔属性1';
comment on column qrtz_simprop_triggers.bool_prop_2 is '布尔属性2';

-- ============================================
-- 6. Blob触发器表 (Blob Triggers)
-- ============================================
create table if not exists qrtz_blob_triggers
(
    sched_name    varchar(120) not null,
    trigger_name  varchar(200) not null,
    trigger_group varchar(200) not null,
    blob_data     bytea,
    primary key (sched_name, trigger_name, trigger_group)
);

comment on table qrtz_blob_triggers is 'Blob触发器表，用于存储自定义触发器类型的序列化数据';
comment on column qrtz_blob_triggers.sched_name is '调度器名称';
comment on column qrtz_blob_triggers.trigger_name is '触发器名称';
comment on column qrtz_blob_triggers.trigger_group is '触发器组名';
comment on column qrtz_blob_triggers.blob_data is '二进制大对象数据，存储序列化的触发器对象';

-- ============================================
-- 7. 日历表 (Calendars)
-- ============================================
create table if not exists qrtz_calendars
(
    sched_name    varchar(120) not null,
    calendar_name varchar(200) not null,
    calendar      bytea        not null,
    primary key (sched_name, calendar_name)
);

comment on table qrtz_calendars is '日历表，存储日历对象，用于在触发器中排除特定日期（如节假日）';
comment on column qrtz_calendars.sched_name is '调度器名称';
comment on column qrtz_calendars.calendar_name is '日历名称';
comment on column qrtz_calendars.calendar is '序列化的日历对象，用于定义排除日期';

-- ============================================
-- 8. 暂停的触发器组表 (Paused Trigger Groups)
-- ============================================
create table if not exists qrtz_paused_trigger_grps
(
    sched_name    varchar(120) not null,
    trigger_group varchar(200) not null,
    primary key (sched_name, trigger_group)
);

comment on table qrtz_paused_trigger_grps is '暂停的触发器组表，记录被暂停的触发器组';
comment on column qrtz_paused_trigger_grps.sched_name is '调度器名称';
comment on column qrtz_paused_trigger_grps.trigger_group is '暂停的触发器组名';

-- ============================================
-- 9. 已触发的触发器表 (Fired Triggers)
-- ============================================
create table if not exists qrtz_fired_triggers
(
    sched_name        varchar(120) not null,
    entry_id          varchar(200) not null,
    trigger_name      varchar(200) not null,
    trigger_group     varchar(200) not null,
    instance_name     varchar(200) not null,
    fired_time        bigint       not null,
    sched_time        bigint       not null,
    priority          integer      not null,
    state             varchar(16)  not null,
    job_name          varchar(200),
    job_group         varchar(200),
    is_nonconcurrent  boolean,
    requests_recovery boolean,
    primary key (sched_name, entry_id)
);

comment on table qrtz_fired_triggers is '已触发的触发器表，记录所有已触发但尚未完成的触发器执行记录';
comment on column qrtz_fired_triggers.sched_name is '调度器名称';
comment on column qrtz_fired_triggers.entry_id is '条目ID，唯一标识一次触发';
comment on column qrtz_fired_triggers.trigger_name is '触发器名称';
comment on column qrtz_fired_triggers.trigger_group is '触发器组名';
comment on column qrtz_fired_triggers.instance_name is '调度器实例名称';
comment on column qrtz_fired_triggers.fired_time is '触发时间（时间戳，毫秒）';
comment on column qrtz_fired_triggers.sched_time is '调度时间（时间戳，毫秒）';
comment on column qrtz_fired_triggers.priority is '优先级';
comment on column qrtz_fired_triggers.state is '状态：EXECUTING, COMPLETE, BLOCKED, ERROR, PAUSED, PAUSED_BLOCKED, ACQUIRED';
comment on column qrtz_fired_triggers.job_name is '关联的作业名称';
comment on column qrtz_fired_triggers.job_group is '关联的作业组名';
comment on column qrtz_fired_triggers.is_nonconcurrent is '是否非并发执行';
comment on column qrtz_fired_triggers.requests_recovery is '是否请求恢复';

create index if not exists idx_qrtz_ft_trig_inst_name
    on qrtz_fired_triggers (sched_name, instance_name);

create index if not exists idx_qrtz_ft_inst_job_req_rcvry
    on qrtz_fired_triggers (sched_name, instance_name, requests_recovery);

create index if not exists idx_qrtz_ft_j_g
    on qrtz_fired_triggers (sched_name, job_name, job_group);

create index if not exists idx_qrtz_ft_jg
    on qrtz_fired_triggers (sched_name, job_group);

create index if not exists idx_qrtz_ft_t_g
    on qrtz_fired_triggers (sched_name, trigger_name, trigger_group);

create index if not exists idx_qrtz_ft_tg
    on qrtz_fired_triggers (sched_name, trigger_group);

-- ============================================
-- 10. 调度器状态表 (Scheduler State)
-- ============================================
create table if not exists qrtz_scheduler_state
(
    sched_name        varchar(120) not null,
    instance_name     varchar(200) not null,
    last_checkin_time bigint       not null,
    checkin_interval  bigint       not null,
    primary key (sched_name, instance_name)
);

comment on table qrtz_scheduler_state is '调度器状态表，用于集群环境下记录各个调度器实例的心跳状态';
comment on column qrtz_scheduler_state.sched_name is '调度器名称';
comment on column qrtz_scheduler_state.instance_name is '调度器实例名称';
comment on column qrtz_scheduler_state.last_checkin_time is '最后检查时间（时间戳，毫秒）';
comment on column qrtz_scheduler_state.checkin_interval is '检查间隔（毫秒）';

-- ============================================
-- 11. 锁表 (Locks)
-- ============================================
create table if not exists qrtz_locks
(
    sched_name varchar(120) not null,
    lock_name  varchar(40)  not null,
    primary key (sched_name, lock_name)
);

comment on table qrtz_locks is '锁表，用于集群环境下实现分布式锁，保证同一时间只有一个实例执行关键操作';
comment on column qrtz_locks.sched_name is '调度器名称';
comment on column qrtz_locks.lock_name is '锁名称，如STATE_ACCESS, TRIGGER_ACCESS';

-- ============================================
-- 初始化数据
-- ============================================
insert into qrtz_locks (sched_name, lock_name)
values  ('DolphinScheduler', 'STATE_ACCESS'),
        ('DolphinScheduler', 'TRIGGER_ACCESS');

