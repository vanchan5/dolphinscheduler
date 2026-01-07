/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.engine.workflow.trigger;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.common.enums.Flag;
import org.apache.dolphinscheduler.common.enums.WarningType;
import org.apache.dolphinscheduler.common.enums.WorkflowExecutionStatus;
import org.apache.dolphinscheduler.common.utils.DateUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.dao.utils.EnvironmentUtils;
import org.apache.dolphinscheduler.dao.utils.WorkerGroupUtils;
import org.apache.dolphinscheduler.extract.master.command.BackfillWorkflowCommandParam;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowBackfillTriggerRequest;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowBackfillTriggerResponse;

import org.apache.commons.lang3.ObjectUtils;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Backfill trigger of the workflow, used to trigger the workflow and generate the workflow instance in the backfill way.
 * 工作流的回填触发器，用于触发工作流并以回填的方式生成工作流实例
 *
 * 前端执行任务选择补数据类型
 * 补数据模式用于批量处理历史时间点的数据
 * 在串行模式下，Listener 实现链式触发，确保按顺序执行所有时间点
 * 这是数据平台中常见的“数据回填”功能，用于处理历史数据或修复问题数据
 *
 * 参数替换 → 任务中的时间占位符（$[yyyyMMdd]）替换为历史时间
 * 通过 scheduleTime 字段，系统知道这是处理历史时间点的数据，任务执行时会使用这个历史时间点来替换所有时间占位符，从而实现对历史数据的处理。
 *
 * 补数据 vs 正常执行的区别
 * 特性	        正常执行 (START_PROCESS)	        补数据 (COMPLEMENT_DATA)
 * 时间点	        当前时间或指定单个时间	            多个历史时间点
 * 执行方式	        单次执行	                        批量执行多个时间点
 * 调度时间	        工作流实例的scheduleTime          每个时间点对应一个实例
 * 链式触发	        不需要	                        串行模式下需要（通过 Listener）
 * 使用场景	        日常调度、手动触发	                历史数据回填、数据修复
 */
@Component
public class WorkflowBackfillTrigger
        extends
            AbstractWorkflowTrigger<WorkflowBackfillTriggerRequest, WorkflowBackfillTriggerResponse> {

    /**
     * 补数时间来源:{@link org.apache.dolphinscheduler.api.validator.workflow.BackfillWorkflowRequestTransformer#transformBackfillParamsDTO(org.apache.dolphinscheduler.api.dto.workflow.WorkflowBackFillRequest)}
     * 前端执行触发
     *
     * @param backfillTriggerRequest
     * @return
     */
    @Override
    protected WorkflowInstance constructWorkflowInstance(WorkflowBackfillTriggerRequest backfillTriggerRequest) {
        final CommandType commandType = CommandType.COMPLEMENT_DATA;
        final Long workflowCode = backfillTriggerRequest.getWorkflowCode();
        final Integer workflowVersion = backfillTriggerRequest.getWorkflowVersion();
        final List<String> backfillTimeList = backfillTriggerRequest.getBackfillTimeList();
        final WorkflowDefinition workflowDefinition = getProcessDefinition(workflowCode, workflowVersion);

        final WorkflowInstance workflowInstance = new WorkflowInstance();
        workflowInstance.setWorkflowDefinitionCode(workflowDefinition.getCode());
        workflowInstance.setWorkflowDefinitionVersion(workflowDefinition.getVersion());
        workflowInstance.setProjectCode(workflowDefinition.getProjectCode());
        workflowInstance.setCommandType(commandType);
        workflowInstance.setStateWithDesc(WorkflowExecutionStatus.SUBMITTED_SUCCESS, commandType.name());
        workflowInstance.setRecovery(Flag.NO);
        workflowInstance.setScheduleTime(DateUtils.stringToDate(backfillTimeList.get(0)));// 只取第一个时间点作为当前实例的调度时间
        workflowInstance.setStartTime(new Date());
        workflowInstance.setRestartTime(workflowInstance.getStartTime());
        workflowInstance.setRunTimes(1);
        workflowInstance.setName(String.join("-", workflowDefinition.getName(), DateUtils.getCurrentTimeStamp()));
        workflowInstance.setTaskDependType(backfillTriggerRequest.getTaskDependType());
        workflowInstance.setFailureStrategy(backfillTriggerRequest.getFailureStrategy());
        workflowInstance
                .setWarningType(ObjectUtils.defaultIfNull(backfillTriggerRequest.getWarningType(), WarningType.NONE));
        workflowInstance.setWarningGroupId(backfillTriggerRequest.getWarningGroupId());
        workflowInstance.setExecutorId(backfillTriggerRequest.getUserId());
        workflowInstance.setExecutorName(getExecutorUser(backfillTriggerRequest.getUserId()).getUserName());
        workflowInstance.setTenantCode(backfillTriggerRequest.getTenantCode());
        workflowInstance.setIsSubWorkflow(Flag.NO);
        workflowInstance.addHistoryCmd(commandType);
        workflowInstance.setWorkflowInstancePriority(backfillTriggerRequest.getWorkflowInstancePriority());
        workflowInstance
                .setWorkerGroup(WorkerGroupUtils.getWorkerGroupOrDefault(backfillTriggerRequest.getWorkerGroup()));
        workflowInstance.setEnvironmentCode(
                EnvironmentUtils.getEnvironmentCodeOrDefault(backfillTriggerRequest.getEnvironmentCode()));
        workflowInstance.setTimeout(workflowDefinition.getTimeout());
        workflowInstance.setDryRun(backfillTriggerRequest.getDryRun().getCode());
        workflowInstance.setTestFlag(backfillTriggerRequest.getTestFlag().getCode());
        return workflowInstance;
    }

    @Override
    protected Command constructTriggerCommand(WorkflowBackfillTriggerRequest backfillTriggerRequest,
                                              WorkflowInstance workflowInstance) {
        final BackfillWorkflowCommandParam backfillWorkflowCommandParam = BackfillWorkflowCommandParam.builder()
                .commandParams(backfillTriggerRequest.getStartParamList())
                .startNodes(backfillTriggerRequest.getStartNodes())
                .timeZone(DateUtils.getTimezone())
                .backfillTimeList(backfillTriggerRequest.getBackfillTimeList())// 但整个时间列表都保存在CommandParam中
                .build();
        return Command.builder()
                .commandType(CommandType.COMPLEMENT_DATA)
                .workflowDefinitionCode(backfillTriggerRequest.getWorkflowCode())
                .workflowDefinitionVersion(backfillTriggerRequest.getWorkflowVersion())
                .workflowInstanceId(workflowInstance.getId())
                .workflowInstancePriority(workflowInstance.getWorkflowInstancePriority())
                .commandParam(JSONUtils.toJsonString(backfillWorkflowCommandParam))
                .build();
    }

    @Override
    protected WorkflowBackfillTriggerResponse onTriggerSuccess(WorkflowInstance workflowInstance) {
        return WorkflowBackfillTriggerResponse.success(workflowInstance.getId());
    }
}
