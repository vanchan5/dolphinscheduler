select * from qrtz_job_details;
select * from qrtz_triggers;
select * from qrtz_cron_triggers;
select * from qrtz_blob_triggers;
select * from qrtz_simple_triggers;
select * from qrtz_simprop_triggers;
select * from qrtz_calendars;
select * from qrtz_paused_trigger_grps;
select * from qrtz_fired_triggers;
select * from qrtz_scheduler_state;
select * from qrtz_locks;
select * from t_ds_schedules;


-- workflowGraph构建流程
select *
from t_ds_workflow_definition
order by id desc;

select create_time,post_task_code,post_task_version,pre_task_code,pre_task_version,*
from t_ds_workflow_task_relation_log
where workflow_definition_code = '162649908989760' and workflow_definition_version = 1
order by create_time desc;

select *
from t_ds_task_definition_log;

select distinct tdtdl.*
from t_ds_task_definition_log tdtdl
         inner join t_ds_workflow_task_relation_log tdwtrl on tdwtrl.post_task_code = tdtdl.code and
                                                              tdwtrl.post_task_version = tdtdl.version
where workflow_definition_code = '162649908989760' and workflow_definition_version=1
  and tdwtrl.post_task_code > 0
;

select start_time,*
from t_ds_workflow_instance
order by start_time desc;

select *
from t_ds_workflow_instance
;

select *
from t_ds_workflow_definition;

select *
from t_ds_task_definition;

select post_task_code,post_task_version,pre_task_code,pre_task_version,*
from t_ds_workflow_task_relation_log tdwtrl
where workflow_definition_code = '160443746112000'
  and workflow_definition_version=3;

-- pre_task_code = 0 说明是开始节点,没有前置节点,post_task_code为自身code
-- post_task_code != 0 说明有前置节点
-- post_task_code != 0 说明有前置节点
select distinct tdtdl.*
from t_ds_task_definition_log tdtdl
         inner join t_ds_workflow_task_relation_log tdwtrl on tdwtrl.post_task_code = tdtdl.code and
                                                              tdwtrl.post_task_version = tdtdl.version
where workflow_definition_code = '160443746112000' and workflow_definition_version=3
  and tdwtrl.post_task_code > 0;