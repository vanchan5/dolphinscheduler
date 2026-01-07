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

package org.apache.dolphinscheduler.api.executor.workflow;

import org.apache.dolphinscheduler.api.controller.ExecutorController;
import org.apache.dolphinscheduler.api.enums.ExecuteType;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.impl.ExecutorServiceImpl;
import org.apache.dolphinscheduler.common.enums.WorkflowExecutionStatus;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.dao.repository.WorkflowInstanceDao;
import org.apache.dolphinscheduler.extract.base.client.Clients;
import org.apache.dolphinscheduler.extract.master.IWorkflowControlClient;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowInstancePauseRequest;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowInstancePauseResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PauseWorkflowInstanceExecutorDelegate
        implements
            IExecutorDelegate<PauseWorkflowInstanceExecutorDelegate.PauseWorkflowInstanceOperation, Void> {

    @Autowired
    private WorkflowInstanceDao workflowInstanceDao;

    /**
     * 前端触发暂停操作 {@link ExecutorServiceImpl#controlWorkflowInstance(User, Integer, ExecuteType)}
     *
     * 状态{@link WorkflowExecutionStatus}
     *
     * 用户请求暂停
     *     ↓
     * API 层 (ExecutorController){@link ExecutorController#controlWorkflowInstance(User, Integer, ExecuteType)}
     *     ↓
     * PauseWorkflowInstanceExecutorDelegate
     *     ↓
     * RPC 调用 Master (WorkflowControlClient)
     *     ↓
     * WorkflowExecutionRunnable.pause()
     *     ↓
     * 发布 WorkflowPauseLifecycleEvent
     *     ↓
     * WorkflowPauseLifecycleEventHandler
     *     ↓
     * WorkflowRunningStateAction.pauseEventAction()
     *     ↓
     * 1. 状态转换: RUNNING_EXECUTION → READY_PAUSE
     * 2. 暂停活跃任务: pauseActiveTask()
     *
     * READY_PAUSE 是中间状态，表示“准备暂停”，正在等待所有活跃任务暂停完成
     * 当所有活跃任务都暂停后，工作流状态会从 READY_PAUSE 转换为 PAUSE（最终状态）
     * 这个设计允许异步暂停，避免阻塞等待所有任务立即暂停
     *
     * @param workflowInstanceControlRequest
     * @return
     */
    @Override
    public Void execute(PauseWorkflowInstanceOperation workflowInstanceControlRequest) {
        final WorkflowInstance workflowInstance = workflowInstanceControlRequest.workflowInstance;
        exceptionIfWorkflowInstanceCannotPause(workflowInstance);
        /**{@link WorkflowExecutionStatus#SERIAL_WAIT}*/
        if (ifWorkflowInstanceCanDirectPauseInDB(workflowInstance)) {
            //工作流状态可以直接在数据库中暂停（canDirectPauseInDB()），直接更新数据库状态为 PAUSE
            directPauseInDB(workflowInstance);
        } else {
            //  Master 处理暂停请求
            pauseInMaster(workflowInstance);
        }
        return null;
    }

    private void exceptionIfWorkflowInstanceCannotPause(WorkflowInstance workflowInstance) {
        WorkflowExecutionStatus workflowInstanceState = workflowInstance.getState();
        if (workflowInstanceState.canPause()) {
            return;
        }
        throw new ServiceException(
                "The workflow instance: " + workflowInstance.getName() + " status is " + workflowInstanceState
                        + ", can not pause");
    }

    private boolean ifWorkflowInstanceCanDirectPauseInDB(WorkflowInstance workflowInstance) {
        return workflowInstance.getState().canDirectPauseInDB();
    }

    private void directPauseInDB(WorkflowInstance workflowInstance) {
        workflowInstanceDao.updateWorkflowInstanceState(
                workflowInstance.getId(),
                workflowInstance.getState(),
                WorkflowExecutionStatus.PAUSE);
        log.info("Update workflow instance {} state from: {} to {} success",
                workflowInstance.getName(),
                workflowInstance.getState().name(),
                WorkflowExecutionStatus.PAUSE.name());
    }

    private void pauseInMaster(WorkflowInstance workflowInstance) {
        try {
            final WorkflowInstancePauseResponse pauseResponse = Clients
                    .withService(IWorkflowControlClient.class)
                    .withHost(workflowInstance.getHost())
                    .pauseWorkflowInstance(new WorkflowInstancePauseRequest(workflowInstance.getId()));

            if (pauseResponse != null && pauseResponse.isSuccess()) {
                log.info("WorkflowInstance: {} pause success", workflowInstance.getName());
            } else {
                throw new ServiceException(
                        "WorkflowInstance: " + workflowInstance.getName() + " pause failed: " + pauseResponse);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(
                    String.format("WorkflowInstance: %s pause failed", workflowInstance.getName()), e);
        }
    }

    public static class PauseWorkflowInstanceOperation {

        private final PauseWorkflowInstanceExecutorDelegate pauseWorkflowInstanceExecutorDelegate;

        private WorkflowInstance workflowInstance;

        private User executeUser;

        public PauseWorkflowInstanceOperation(PauseWorkflowInstanceExecutorDelegate pauseWorkflowInstanceExecutorDelegate) {
            this.pauseWorkflowInstanceExecutorDelegate = pauseWorkflowInstanceExecutorDelegate;
        }

        public PauseWorkflowInstanceExecutorDelegate.PauseWorkflowInstanceOperation onWorkflowInstance(WorkflowInstance workflowInstance) {
            this.workflowInstance = workflowInstance;
            return this;
        }

        public PauseWorkflowInstanceExecutorDelegate.PauseWorkflowInstanceOperation byUser(User executeUser) {
            this.executeUser = executeUser;
            return this;
        }

        public void execute() {
            pauseWorkflowInstanceExecutorDelegate.execute(this);
        }
    }
}
