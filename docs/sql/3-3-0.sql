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

alter table qrtz_job_details
    owner to root;

create index if not exists idx_qrtz_j_req_recovery
    on qrtz_job_details (sched_name, requests_recovery);

create index if not exists idx_qrtz_j_grp
    on qrtz_job_details (sched_name, job_group);

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

alter table qrtz_triggers
    owner to root;

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

alter table qrtz_simple_triggers
    owner to root;

create table if not exists qrtz_cron_triggers
(
    sched_name      varchar(120) not null,
    trigger_name    varchar(200) not null,
    trigger_group   varchar(200) not null,
    cron_expression varchar(120) not null,
    time_zone_id    varchar(80),
    primary key (sched_name, trigger_name, trigger_group)
);

alter table qrtz_cron_triggers
    owner to root;

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

alter table qrtz_simprop_triggers
    owner to root;

create table if not exists qrtz_blob_triggers
(
    sched_name    varchar(120) not null,
    trigger_name  varchar(200) not null,
    trigger_group varchar(200) not null,
    blob_data     bytea,
    primary key (sched_name, trigger_name, trigger_group)
);

alter table qrtz_blob_triggers
    owner to root;

create table if not exists qrtz_calendars
(
    sched_name    varchar(120) not null,
    calendar_name varchar(200) not null,
    calendar      bytea        not null,
    primary key (sched_name, calendar_name)
);

alter table qrtz_calendars
    owner to root;

create table if not exists qrtz_paused_trigger_grps
(
    sched_name    varchar(120) not null,
    trigger_group varchar(200) not null,
    primary key (sched_name, trigger_group)
);

alter table qrtz_paused_trigger_grps
    owner to root;

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

alter table qrtz_fired_triggers
    owner to root;

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

create table if not exists qrtz_scheduler_state
(
    sched_name        varchar(120) not null,
    instance_name     varchar(200) not null,
    last_checkin_time bigint       not null,
    checkin_interval  bigint       not null,
    primary key (sched_name, instance_name)
);

alter table qrtz_scheduler_state
    owner to root;

create table if not exists qrtz_locks
(
    sched_name varchar(120) not null,
    lock_name  varchar(40)  not null,
    primary key (sched_name, lock_name)
);

alter table qrtz_locks
    owner to root;

create table if not exists t_ds_access_token
(
    id          integer     default nextval('t_ds_access_token_id_sequence'::regclass) not null
        primary key,
    user_id     integer,
    token       varchar(64) default NULL::character varying,
    expire_time timestamp,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_access_token
    owner to root;

create table if not exists t_ds_alert
(
    id                       integer      default nextval('t_ds_alert_id_sequence'::regclass) not null
        primary key,
    title                    varchar(512) default NULL::character varying,
    sign                     varchar(40)  default ''::character varying                       not null,
    content                  text,
    alert_status             integer      default 0,
    warning_type             integer      default 2,
    log                      text,
    alertgroup_id            integer,
    create_time              timestamp,
    update_time              timestamp,
    project_code             bigint,
    workflow_definition_code bigint,
    workflow_instance_id     integer,
    alert_type               integer
);

alter table t_ds_alert
    owner to root;

create index if not exists idx_status
    on t_ds_alert (alert_status);

create index if not exists idx_sign
    on t_ds_alert (sign);

create table if not exists t_ds_alertgroup
(
    id                 integer      default nextval('t_ds_alertgroup_id_sequence'::regclass) not null
        primary key,
    alert_instance_ids varchar(255) default NULL::character varying,
    create_user_id     integer,
    group_name         varchar(255) default NULL::character varying
        constraint t_ds_alertgroup_name_un
            unique,
    description        varchar(255) default NULL::character varying,
    create_time        timestamp,
    update_time        timestamp
);

alter table t_ds_alertgroup
    owner to root;

create table if not exists t_ds_command
(
    id                          integer     default nextval('t_ds_command_id_sequence'::regclass) not null
        primary key,
    command_type                integer,
    workflow_definition_code    bigint                                                            not null,
    command_param               text,
    task_depend_type            integer,
    failure_strategy            integer     default 0,
    warning_type                integer     default 0,
    warning_group_id            integer,
    schedule_time               timestamp,
    start_time                  timestamp,
    executor_id                 integer,
    update_time                 timestamp,
    workflow_instance_priority  integer     default 2,
    worker_group                varchar(255),
    tenant_code                 varchar(64) default 'default'::character varying,
    environment_code            bigint      default '-1'::bigint,
    dry_run                     integer     default 0,
    workflow_instance_id        integer     default 0,
    workflow_definition_version integer     default 0,
    test_flag                   integer
);

alter table t_ds_command
    owner to root;

create index if not exists priority_id_index
    on t_ds_command (workflow_instance_priority, id);

create table if not exists t_ds_datasource
(
    id                integer      default nextval('t_ds_datasource_id_sequence'::regclass) not null
        primary key,
    name              varchar(64)                                                           not null,
    note              varchar(255) default NULL::character varying,
    type              integer                                                               not null,
    user_id           integer                                                               not null,
    connection_params text                                                                  not null,
    create_time       timestamp                                                             not null,
    update_time       timestamp,
    constraint t_ds_datasource_name_un
        unique (name, type)
);

alter table t_ds_datasource
    owner to root;

create table if not exists t_ds_error_command
(
    id                          integer not null
        primary key,
    command_type                integer,
    workflow_definition_code    bigint  not null,
    command_param               text,
    task_depend_type            integer,
    failure_strategy            integer     default 0,
    warning_type                integer     default 0,
    warning_group_id            integer,
    schedule_time               timestamp,
    start_time                  timestamp,
    executor_id                 integer,
    update_time                 timestamp,
    workflow_instance_priority  integer     default 2,
    worker_group                varchar(255),
    tenant_code                 varchar(64) default 'default'::character varying,
    environment_code            bigint      default '-1'::bigint,
    dry_run                     integer     default 0,
    message                     text,
    workflow_instance_id        integer     default 0,
    workflow_definition_version integer     default 0,
    test_flag                   integer
);

alter table t_ds_error_command
    owner to root;

create table if not exists t_ds_workflow_definition
(
    id               integer      default nextval('t_ds_workflow_definition_id_sequence'::regclass) not null
        primary key,
    code             bigint                                                                         not null,
    name             varchar(255) default NULL::character varying,
    version          integer      default 1                                                         not null,
    description      text,
    project_code     bigint,
    release_state    integer,
    user_id          integer,
    global_params    text,
    locations        text,
    warning_group_id integer,
    flag             integer,
    timeout          integer      default 0,
    execution_type   integer      default 0,
    create_time      timestamp,
    update_time      timestamp,
    constraint workflow_definition_unique
        unique (name, project_code)
);

alter table t_ds_workflow_definition
    owner to root;

create index if not exists workflow_definition_index
    on t_ds_workflow_definition (code, id);

create index if not exists workflow_definition_index_project_code
    on t_ds_workflow_definition (project_code);

create table if not exists t_ds_workflow_definition_log
(
    id               integer      default nextval('t_ds_workflow_definition_log_id_sequence'::regclass) not null
        primary key,
    code             bigint                                                                             not null,
    name             varchar(255) default NULL::character varying,
    version          integer      default 1                                                             not null,
    description      text,
    project_code     bigint,
    release_state    integer,
    user_id          integer,
    global_params    text,
    locations        text,
    warning_group_id integer,
    flag             integer,
    timeout          integer      default 0,
    execution_type   integer      default 0,
    operator         integer,
    operate_time     timestamp,
    create_time      timestamp,
    update_time      timestamp
);

alter table t_ds_workflow_definition_log
    owner to root;

create unique index if not exists uniq_idx_code_version
    on t_ds_workflow_definition_log (code, version);

create index if not exists workflow_definition_log_index_project_code
    on t_ds_workflow_definition_log (project_code);

create table if not exists t_ds_task_definition
(
    id                      integer      default nextval('t_ds_task_definition_id_sequence'::regclass) not null
        primary key,
    code                    bigint                                                                     not null,
    name                    varchar(255) default NULL::character varying,
    version                 integer      default 1                                                     not null,
    description             text,
    project_code            bigint,
    user_id                 integer,
    task_type               varchar(50)  default NULL::character varying,
    task_execute_type       integer      default 0,
    task_params             text,
    flag                    integer,
    task_priority           integer      default 2,
    worker_group            varchar(255) default NULL::character varying,
    environment_code        bigint       default '-1'::bigint,
    fail_retry_times        integer,
    fail_retry_interval     integer,
    timeout_flag            integer,
    timeout_notify_strategy integer,
    timeout                 integer      default 0,
    delay_time              integer      default 0,
    task_group_id           integer,
    task_group_priority     integer      default 0,
    resource_ids            text,
    cpu_quota               integer      default '-1'::integer                                         not null,
    memory_max              integer      default '-1'::integer                                         not null,
    create_time             timestamp,
    update_time             timestamp
);

alter table t_ds_task_definition
    owner to root;

create index if not exists task_definition_index
    on t_ds_task_definition (project_code, id);

create table if not exists t_ds_task_definition_log
(
    id                      integer      default nextval('t_ds_task_definition_log_id_sequence'::regclass) not null
        primary key,
    code                    bigint                                                                         not null,
    name                    varchar(255) default NULL::character varying,
    version                 integer      default 1                                                         not null,
    description             text,
    project_code            bigint,
    user_id                 integer,
    task_type               varchar(50)  default NULL::character varying,
    task_execute_type       integer      default 0,
    task_params             text,
    flag                    integer,
    task_priority           integer      default 2,
    worker_group            varchar(255) default NULL::character varying,
    environment_code        bigint       default '-1'::bigint,
    fail_retry_times        integer,
    fail_retry_interval     integer,
    timeout_flag            integer,
    timeout_notify_strategy integer,
    timeout                 integer      default 0,
    delay_time              integer      default 0,
    resource_ids            text,
    operator                integer,
    task_group_id           integer,
    task_group_priority     integer      default 0,
    operate_time            timestamp,
    cpu_quota               integer      default '-1'::integer                                             not null,
    memory_max              integer      default '-1'::integer                                             not null,
    create_time             timestamp,
    update_time             timestamp
);

alter table t_ds_task_definition_log
    owner to root;

create index if not exists idx_task_definition_log_code_version
    on t_ds_task_definition_log (code, version);

create index if not exists idx_task_definition_log_project_code
    on t_ds_task_definition_log (project_code);

create table if not exists t_ds_workflow_task_relation
(
    id                          integer      default nextval('t_ds_workflow_task_relation_id_sequence'::regclass) not null
        primary key,
    name                        varchar(255) default NULL::character varying,
    project_code                bigint,
    workflow_definition_code    bigint,
    workflow_definition_version integer,
    pre_task_code               bigint,
    pre_task_version            integer      default 0,
    post_task_code              bigint,
    post_task_version           integer      default 0,
    condition_type              integer,
    condition_params            text,
    create_time                 timestamp,
    update_time                 timestamp
);

alter table t_ds_workflow_task_relation
    owner to root;

create index if not exists workflow_task_relation_idx_project_code_workflow_definition_cod
    on t_ds_workflow_task_relation (project_code, workflow_definition_code);

create index if not exists workflow_task_relation_idx_pre_task_code_version
    on t_ds_workflow_task_relation (pre_task_code, pre_task_version);

create index if not exists workflow_task_relation_idx_post_task_code_version
    on t_ds_workflow_task_relation (post_task_code, post_task_version);

create table if not exists t_ds_workflow_task_relation_log
(
    id                          integer      default nextval('t_ds_workflow_task_relation_log_id_sequence'::regclass) not null
        primary key,
    name                        varchar(255) default NULL::character varying,
    project_code                bigint,
    workflow_definition_code    bigint,
    workflow_definition_version integer,
    pre_task_code               bigint,
    pre_task_version            integer      default 0,
    post_task_code              bigint,
    post_task_version           integer      default 0,
    condition_type              integer,
    condition_params            text,
    operator                    integer,
    operate_time                timestamp,
    create_time                 timestamp,
    update_time                 timestamp
);

alter table t_ds_workflow_task_relation_log
    owner to root;

create index if not exists workflow_task_relation_log_idx_project_code_workflow_definition
    on t_ds_workflow_task_relation_log (project_code, workflow_definition_code);

create table if not exists t_ds_workflow_instance
(
    id                          integer      default nextval('t_ds_workflow_instance_id_sequence'::regclass) not null
        primary key,
    name                        varchar(255) default NULL::character varying,
    workflow_definition_code    bigint,
    workflow_definition_version integer      default 1                                                       not null,
    project_code                bigint,
    state                       integer,
    state_history               text,
    recovery                    integer,
    start_time                  timestamp,
    end_time                    timestamp,
    run_times                   integer,
    host                        varchar(135) default NULL::character varying,
    command_type                integer,
    command_param               text,
    task_depend_type            integer,
    max_try_times               integer      default 0,
    failure_strategy            integer      default 0,
    warning_type                integer      default 0,
    warning_group_id            integer,
    schedule_time               timestamp,
    command_start_time          timestamp,
    global_params               text,
    workflow_instance_json      text,
    flag                        integer      default 1,
    update_time                 timestamp,
    is_sub_workflow             integer      default 0,
    executor_id                 integer                                                                      not null,
    executor_name               varchar(64)  default NULL::character varying,
    history_cmd                 text,
    dependence_schedule_times   text,
    workflow_instance_priority  integer      default 2,
    worker_group                varchar(255),
    environment_code            bigint       default '-1'::bigint,
    timeout                     integer      default 0,
    tenant_code                 varchar(64)  default 'default'::character varying,
    var_pool                    text,
    dry_run                     integer      default 0,
    next_workflow_instance_id   integer      default 0,
    restart_time                timestamp,
    test_flag                   integer
);

alter table t_ds_workflow_instance
    owner to root;

create index if not exists workflow_instance_index
    on t_ds_workflow_instance (workflow_definition_code, id);

create index if not exists start_time_index
    on t_ds_workflow_instance (start_time, end_time);

create table if not exists t_ds_project
(
    id          integer      default nextval('t_ds_project_id_sequence'::regclass) not null
        primary key,
    name        varchar(255) default NULL::character varying,
    code        bigint                                                             not null,
    description varchar(255) default NULL::character varying,
    user_id     integer,
    flag        integer      default 1,
    create_time timestamp    default CURRENT_TIMESTAMP,
    update_time timestamp    default CURRENT_TIMESTAMP
);

alter table t_ds_project
    owner to root;

create index if not exists user_id_index
    on t_ds_project (user_id);

create unique index if not exists unique_name
    on t_ds_project (name);

create unique index if not exists unique_code
    on t_ds_project (code);

create table if not exists t_ds_project_parameter
(
    id              integer     default nextval('t_ds_project_parameter_id_sequence'::regclass) not null
        primary key,
    param_name      varchar(255)                                                                not null,
    param_value     text                                                                        not null,
    param_data_type varchar(50) default 'VARCHAR'::character varying,
    code            bigint                                                                      not null,
    project_code    bigint                                                                      not null,
    user_id         integer,
    operator        integer,
    create_time     timestamp   default CURRENT_TIMESTAMP,
    update_time     timestamp   default CURRENT_TIMESTAMP
);

alter table t_ds_project_parameter
    owner to root;

create unique index if not exists unique_project_parameter_name
    on t_ds_project_parameter (project_code, param_name);

create unique index if not exists unique_project_parameter_code
    on t_ds_project_parameter (code);

create table if not exists t_ds_project_preference
(
    id           integer   default nextval('t_ds_project_preference_id_sequence'::regclass) not null
        primary key,
    code         bigint                                                                     not null,
    project_code bigint                                                                     not null,
    preferences  varchar(512)                                                               not null,
    user_id      integer,
    state        integer   default 1,
    create_time  timestamp default CURRENT_TIMESTAMP,
    update_time  timestamp default CURRENT_TIMESTAMP
);

alter table t_ds_project_preference
    owner to root;

create unique index if not exists unique_project_preference_project_code
    on t_ds_project_preference (project_code);

create unique index if not exists unique_project_preference_code
    on t_ds_project_preference (code);

create table if not exists t_ds_queue
(
    id          integer     default nextval('t_ds_queue_id_sequence'::regclass) not null
        primary key,
    queue_name  varchar(64) default NULL::character varying,
    queue       varchar(64) default NULL::character varying,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_queue
    owner to root;

create unique index if not exists unique_queue_name
    on t_ds_queue (queue_name);

create table if not exists t_ds_relation_datasource_user
(
    id            integer default nextval('t_ds_relation_datasource_user_id_sequence'::regclass) not null
        primary key,
    user_id       integer                                                                        not null,
    datasource_id integer,
    perm          integer default 1,
    create_time   timestamp,
    update_time   timestamp
);

alter table t_ds_relation_datasource_user
    owner to root;

create table if not exists t_ds_relation_workflow_instance
(
    id                          integer default nextval('t_ds_relation_workflow_instance_id_sequence'::regclass) not null
        primary key,
    parent_workflow_instance_id integer,
    parent_task_instance_id     integer,
    workflow_instance_id        integer
);

alter table t_ds_relation_workflow_instance
    owner to root;

create index if not exists idx_relation_workflow_instance_parent_workflow_task
    on t_ds_relation_workflow_instance (parent_workflow_instance_id, parent_task_instance_id);

create index if not exists idx_relation_workflow_instance_workflow_instance_id
    on t_ds_relation_workflow_instance (workflow_instance_id);

create table if not exists t_ds_relation_project_user
(
    id          integer default nextval('t_ds_relation_project_user_id_sequence'::regclass) not null
        primary key,
    user_id     integer                                                                     not null,
    project_id  integer,
    perm        integer default 1,
    create_time timestamp,
    update_time timestamp,
    constraint t_ds_relation_project_user_un
        unique (user_id, project_id)
);

alter table t_ds_relation_project_user
    owner to root;

create index if not exists relation_project_user_id_index
    on t_ds_relation_project_user (user_id);

create table if not exists t_ds_relation_resources_user
(
    id           integer default nextval('t_ds_relation_resources_user_id_sequence'::regclass) not null
        primary key,
    user_id      integer                                                                       not null,
    resources_id integer,
    perm         integer default 1,
    create_time  timestamp,
    update_time  timestamp
);

alter table t_ds_relation_resources_user
    owner to root;

create table if not exists t_ds_relation_udfs_user
(
    id          integer default nextval('t_ds_relation_udfs_user_id_sequence'::regclass) not null
        primary key,
    user_id     integer                                                                  not null,
    udf_id      integer,
    perm        integer default 1,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_relation_udfs_user
    owner to root;

create table if not exists t_ds_resources
(
    id           integer      default nextval('t_ds_resources_id_sequence'::regclass) not null
        primary key,
    alias        varchar(64)  default NULL::character varying,
    file_name    varchar(64)  default NULL::character varying,
    description  varchar(255) default NULL::character varying,
    user_id      integer,
    type         integer,
    size         bigint,
    create_time  timestamp,
    update_time  timestamp,
    pid          integer,
    full_name    varchar(128),
    is_directory boolean      default false,
    constraint t_ds_resources_un
        unique (full_name, type)
);

alter table t_ds_resources
    owner to root;

create table if not exists t_ds_schedules
(
    id                         integer     default nextval('t_ds_schedules_id_sequence'::regclass) not null
        primary key,
    workflow_definition_code   bigint                                                              not null,
    start_time                 timestamp                                                           not null,
    end_time                   timestamp                                                           not null,
    timezone_id                varchar(40) default NULL::character varying,
    crontab                    varchar(255)                                                        not null,
    failure_strategy           integer                                                             not null,
    user_id                    integer                                                             not null,
    release_state              integer                                                             not null,
    warning_type               integer                                                             not null,
    warning_group_id           integer,
    workflow_instance_priority integer     default 2,
    worker_group               varchar(255),
    tenant_code                varchar(64) default 'default'::character varying,
    environment_code           bigint      default '-1'::bigint,
    create_time                timestamp                                                           not null,
    update_time                timestamp                                                           not null
);

alter table t_ds_schedules
    owner to root;

create table if not exists t_ds_session
(
    id              varchar(64) not null
        primary key,
    user_id         integer,
    ip              varchar(45) default NULL::character varying,
    last_login_time timestamp
);

alter table t_ds_session
    owner to root;

create table if not exists t_ds_task_instance
(
    id                      integer      default nextval('t_ds_task_instance_id_sequence'::regclass) not null
        primary key,
    name                    varchar(255) default NULL::character varying,
    task_type               varchar(50)  default NULL::character varying,
    task_execute_type       integer      default 0,
    task_code               bigint                                                                   not null,
    task_definition_version integer      default 1                                                   not null,
    workflow_instance_id    integer,
    workflow_instance_name  varchar(255) default NULL::character varying,
    project_code            bigint,
    state                   integer,
    submit_time             timestamp,
    start_time              timestamp,
    end_time                timestamp,
    host                    varchar(135) default NULL::character varying,
    execute_path            varchar(200) default NULL::character varying,
    log_path                text,
    alert_flag              integer,
    retry_times             integer      default 0,
    pid                     integer,
    app_link                text,
    task_params             text,
    flag                    integer      default 1,
    retry_interval          integer,
    max_retry_times         integer,
    task_instance_priority  integer,
    worker_group            varchar(255),
    environment_code        bigint       default '-1'::bigint,
    environment_config      text,
    executor_id             integer,
    executor_name           varchar(64)  default NULL::character varying,
    first_submit_time       timestamp,
    delay_time              integer      default 0,
    task_group_id           integer,
    var_pool                text,
    dry_run                 integer      default 0,
    cpu_quota               integer      default '-1'::integer                                       not null,
    memory_max              integer      default '-1'::integer                                       not null,
    test_flag               integer
);

alter table t_ds_task_instance
    owner to root;

create index if not exists idx_task_instance_code_version
    on t_ds_task_instance (task_code, task_definition_version);

create table if not exists t_ds_task_instance_context
(
    id               integer      not null
        primary key,
    task_instance_id integer      not null,
    context          text         not null,
    context_type     varchar(200) not null,
    create_time      timestamp    not null,
    update_time      timestamp    not null
);

alter table t_ds_task_instance_context
    owner to root;

create unique index if not exists idx_task_instance_id
    on t_ds_task_instance_context (task_instance_id, context_type);

create table if not exists t_ds_tenant
(
    id          integer      default nextval('t_ds_tenant_id_sequence'::regclass) not null
        primary key,
    tenant_code varchar(64)  default NULL::character varying,
    description varchar(255) default NULL::character varying,
    queue_id    integer,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_tenant
    owner to root;

create unique index if not exists unique_tenant_code
    on t_ds_tenant (tenant_code);

create table if not exists t_ds_udfs
(
    id            integer      default nextval('t_ds_udfs_id_sequence'::regclass) not null
        primary key,
    user_id       integer                                                         not null,
    func_name     varchar(255)                                                    not null,
    class_name    varchar(255)                                                    not null,
    type          integer                                                         not null,
    arg_types     varchar(255) default NULL::character varying,
    database      varchar(255) default NULL::character varying,
    description   varchar(255) default NULL::character varying,
    resource_id   integer                                                         not null,
    resource_name varchar(255)                                                    not null,
    create_time   timestamp                                                       not null,
    update_time   timestamp                                                       not null
);

alter table t_ds_udfs
    owner to root;

create unique index if not exists unique_func_name
    on t_ds_udfs (func_name);

create table if not exists t_ds_user
(
    id            integer     default nextval('t_ds_user_id_sequence'::regclass) not null
        primary key,
    user_name     varchar(64) default NULL::character varying,
    user_password varchar(64) default NULL::character varying,
    user_type     integer,
    email         varchar(64) default NULL::character varying,
    phone         varchar(11) default NULL::character varying,
    tenant_id     integer     default '-1'::integer,
    create_time   timestamp,
    update_time   timestamp,
    queue         varchar(64) default NULL::character varying,
    state         integer     default 1,
    time_zone     varchar(32) default NULL::character varying
);

alter table t_ds_user
    owner to root;

create table if not exists t_ds_version
(
    id      integer default nextval('t_ds_version_id_sequence'::regclass) not null
        primary key,
    version varchar(63)                                                   not null
);

alter table t_ds_version
    owner to root;

create index if not exists version_index
    on t_ds_version (version);

create table if not exists t_ds_worker_group
(
    id          bigint default nextval('t_ds_worker_group_id_sequence'::regclass) not null
        primary key,
    name        varchar(255)                                                      not null
        constraint name_unique
            unique,
    addr_list   text,
    create_time timestamp,
    update_time timestamp,
    description text
);

alter table t_ds_worker_group
    owner to root;

create table if not exists t_ds_relation_project_worker_group
(
    id           integer default nextval('t_ds_relation_project_worker_group_sequence'::regclass) not null
        primary key,
    project_code bigint,
    worker_group varchar(255)                                                                     not null,
    create_time  timestamp,
    update_time  timestamp,
    constraint t_ds_relation_project_worker_group_un
        unique (project_code, worker_group)
);

alter table t_ds_relation_project_worker_group
    owner to root;

create table if not exists t_ds_plugin_define
(
    id            serial
        constraint t_ds_plugin_define_pk
            primary key,
    plugin_name   varchar(255) not null,
    plugin_type   varchar(63)  not null,
    plugin_params text,
    create_time   timestamp,
    update_time   timestamp,
    constraint t_ds_plugin_define_un
        unique (plugin_name, plugin_type)
);

alter table t_ds_plugin_define
    owner to root;

create table if not exists t_ds_alert_plugin_instance
(
    id                     serial
        constraint t_ds_alert_plugin_instance_pk
            primary key,
    plugin_define_id       integer not null,
    plugin_instance_params text,
    create_time            timestamp,
    update_time            timestamp,
    instance_name          varchar(255)
);

alter table t_ds_alert_plugin_instance
    owner to root;

create table if not exists t_ds_environment
(
    id          serial
        primary key,
    code        bigint not null
        constraint environment_code_unique
            unique,
    name        varchar(255) default NULL::character varying
        constraint environment_name_unique
            unique,
    config      text,
    description text,
    operator    integer,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_environment
    owner to root;

create table if not exists t_ds_environment_worker_group_relation
(
    id               serial
        primary key,
    environment_code bigint       not null,
    worker_group     varchar(255) not null,
    operator         integer,
    create_time      timestamp,
    update_time      timestamp,
    constraint environment_worker_group_unique
        unique (environment_code, worker_group)
);

alter table t_ds_environment_worker_group_relation
    owner to root;

create table if not exists t_ds_task_group_queue
(
    id                   serial
        primary key,
    task_id              integer,
    task_name            varchar(255) default NULL::character varying,
    group_id             integer,
    workflow_instance_id integer,
    priority             integer      default 0,
    status               integer      default '-1'::integer,
    force_start          integer      default 0,
    in_queue             integer      default 0,
    create_time          timestamp,
    update_time          timestamp
);

alter table t_ds_task_group_queue
    owner to root;

create index if not exists idx_t_ds_task_group_queue_in_queue
    on t_ds_task_group_queue (in_queue);

create table if not exists t_ds_task_group
(
    id           serial
        primary key,
    name         varchar(255) default NULL::character varying,
    description  varchar(255) default NULL::character varying,
    group_size   integer not null,
    project_code bigint       default '0'::bigint,
    use_size     integer      default 0,
    user_id      integer,
    status       integer      default 1,
    create_time  timestamp,
    update_time  timestamp
);

alter table t_ds_task_group
    owner to root;

create table if not exists t_ds_audit_log
(
    id             serial
        primary key,
    user_id        integer      not null,
    model_id       bigint       not null,
    model_name     varchar(255) not null,
    model_type     varchar(255) not null,
    operation_type varchar(255) not null,
    description    varchar(255) not null,
    latency        integer      not null,
    detail         varchar(255) default NULL::character varying,
    create_time    timestamp
);

alter table t_ds_audit_log
    owner to root;

create table if not exists t_ds_k8s
(
    id          serial
        primary key,
    k8s_name    varchar(255) default NULL::character varying,
    k8s_config  text,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_k8s
    owner to root;

create table if not exists t_ds_k8s_namespace
(
    id           serial
        primary key,
    code         bigint not null,
    namespace    varchar(255) default NULL::character varying,
    user_id      integer,
    cluster_code bigint not null,
    create_time  timestamp,
    update_time  timestamp,
    constraint k8s_namespace_unique
        unique (namespace, cluster_code)
);

alter table t_ds_k8s_namespace
    owner to root;

create table if not exists t_ds_relation_namespace_user
(
    id           serial
        primary key,
    user_id      integer,
    namespace_id integer,
    perm         integer,
    create_time  timestamp,
    update_time  timestamp,
    constraint namespace_user_unique
        unique (user_id, namespace_id)
);

alter table t_ds_relation_namespace_user
    owner to root;

create table if not exists t_ds_alert_send_status
(
    id                       serial
        primary key,
    alert_id                 integer not null,
    alert_plugin_instance_id integer not null,
    send_status              integer default 0,
    log                      text,
    create_time              timestamp,
    constraint alert_send_status_unique
        unique (alert_id, alert_plugin_instance_id)
);

alter table t_ds_alert_send_status
    owner to root;

create table if not exists t_ds_cluster
(
    id          serial
        primary key,
    code        bigint not null
        constraint cluster_code_unique
            unique,
    name        varchar(255) default NULL::character varying
        constraint cluster_name_unique
            unique,
    config      text,
    description text,
    operator    integer,
    create_time timestamp,
    update_time timestamp
);

alter table t_ds_cluster
    owner to root;

create table if not exists t_ds_fav_task
(
    id        serial
        primary key,
    task_type varchar(64) not null,
    user_id   integer     not null
);

alter table t_ds_fav_task
    owner to root;

create table if not exists t_ds_relation_sub_workflow
(
    id                          serial
        primary key,
    parent_workflow_instance_id bigint not null,
    parent_task_code            bigint not null,
    sub_workflow_instance_id    bigint not null
);

alter table t_ds_relation_sub_workflow
    owner to root;

create index if not exists idx_parent_workflow_instance_id
    on t_ds_relation_sub_workflow (parent_workflow_instance_id);

create index if not exists idx_parent_task_code
    on t_ds_relation_sub_workflow (parent_task_code);

create index if not exists idx_sub_workflow_instance_id
    on t_ds_relation_sub_workflow (sub_workflow_instance_id);

create table if not exists t_ds_workflow_task_lineage
(
    id                            integer                             not null
        primary key,
    workflow_definition_code      bigint    default 0                 not null,
    workflow_definition_version   integer   default 0                 not null,
    task_definition_code          bigint    default 0                 not null,
    task_definition_version       integer   default 0                 not null,
    dept_project_code             bigint    default 0                 not null,
    dept_workflow_definition_code bigint    default 0                 not null,
    dept_task_definition_code     bigint    default 0                 not null,
    create_time                   timestamp default CURRENT_TIMESTAMP not null,
    update_time                   timestamp default CURRENT_TIMESTAMP not null
);

alter table t_ds_workflow_task_lineage
    owner to root;

create index if not exists idx_workflow_code_version
    on t_ds_workflow_task_lineage (workflow_definition_code, workflow_definition_version);

create index if not exists idx_task_code_version
    on t_ds_workflow_task_lineage (task_definition_code, task_definition_version);

create index if not exists idx_dept_code
    on t_ds_workflow_task_lineage (dept_project_code, dept_workflow_definition_code, dept_task_definition_code);

create table if not exists t_ds_jdbc_registry_data
(
    id               bigserial
        primary key,
    data_key         varchar                             not null,
    data_value       text                                not null,
    data_type        varchar                             not null,
    client_id        bigint                              not null,
    create_time      timestamp default CURRENT_TIMESTAMP not null,
    last_update_time timestamp default CURRENT_TIMESTAMP not null
);

alter table t_ds_jdbc_registry_data
    owner to root;

create unique index if not exists uk_t_ds_jdbc_registry_datakey
    on t_ds_jdbc_registry_data (data_key);

create table if not exists t_ds_jdbc_registry_lock
(
    id          bigserial
        primary key,
    lock_key    varchar                             not null,
    lock_owner  varchar                             not null,
    client_id   bigint                              not null,
    create_time timestamp default CURRENT_TIMESTAMP not null
);

alter table t_ds_jdbc_registry_lock
    owner to root;

create unique index if not exists uk_t_ds_jdbc_registry_lockkey
    on t_ds_jdbc_registry_lock (lock_key);

create table if not exists t_ds_jdbc_registry_client_heartbeat
(
    id                  bigint                              not null
        primary key,
    client_name         varchar                             not null,
    last_heartbeat_time bigint                              not null,
    connection_config   text                                not null,
    create_time         timestamp default CURRENT_TIMESTAMP not null
);

alter table t_ds_jdbc_registry_client_heartbeat
    owner to root;

create table if not exists t_ds_jdbc_registry_data_change_event
(
    id                 bigserial
        primary key,
    event_type         varchar                             not null,
    jdbc_registry_data text                                not null,
    create_time        timestamp default CURRENT_TIMESTAMP not null
);

alter table t_ds_jdbc_registry_data_change_event
    owner to root;


insert into t_ds_tenant (id, tenant_code, description, queue_id, create_time, update_time)
values  (-1, 'default', 'default tenant', 1, '2018-03-27 15:48:50.000000', '2018-10-24 17:40:22.000000');

insert into t_ds_user (id, user_name, user_password, user_type, email, phone, tenant_id, create_time, update_time, queue, state, time_zone)
values  (1, 'admin', '7ad2410b2f4c074479a8937a28a22b8f', 0, 'xxx@qq.com', '', 1, '2018-03-27 15:48:50.000000', '2025-12-19 16:44:07.522000', 'default', 1, null);

insert into t_ds_alertgroup (id, alert_instance_ids, create_user_id, group_name, description, create_time, update_time)
values  (1, null, 1, 'default admin warning group', 'default admin warning group', '2018-11-29 10:20:39.000000', '2018-11-29 10:20:39.000000');

insert into t_ds_queue (id, queue_name, queue, create_time, update_time)
values  (1, 'default', 'default', '2018-11-29 10:22:33.000000', '2018-11-29 10:22:33.000000');

insert into qrtz_locks (sched_name, lock_name)
values  ('DolphinScheduler', 'STATE_ACCESS'),
        ('DolphinScheduler', 'TRIGGER_ACCESS');

insert into t_ds_version (id, version)
values  (1, '3.3.0-alpha');

