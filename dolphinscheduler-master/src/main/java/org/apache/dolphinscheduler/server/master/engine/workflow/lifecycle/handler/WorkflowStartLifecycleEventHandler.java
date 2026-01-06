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

package org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.common.enums.WorkflowExecutionStatus;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.server.master.engine.ILifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.command.CommandEngine;
import org.apache.dolphinscheduler.server.master.engine.command.handler.AbstractCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.command.handler.WorkflowFailoverCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.WorkflowLifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.event.WorkflowStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.WorkflowExecutionRunnableFactory;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.IWorkflowStateAction;

import lombok.extern.slf4j.Slf4j;

import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.WorkflowRunningStateAction;
import org.apache.dolphinscheduler.server.master.runner.WorkflowExecuteContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowStartLifecycleEventHandler
        extends
            AbstractWorkflowLifecycleEventHandler<WorkflowStartLifecycleEvent> {

    /**
     * 1、工作流事件以及工作流执行状态
     * 工作流开始事件{@link WorkflowStartLifecycleEvent#of(IWorkflowExecutionRunnable)} 只有一个触发点 {@link CommandEngine#bootstrapWorkflowExecutionRunnable(IWorkflowExecutionRunnable)}
     * 此时经过组装{@link WorkflowExecutionRunnableFactory#createWorkflowExecuteRunnable(Command)}
     *          {@link AbstractCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
     * 后大多数状态为 {@link WorkflowExecutionStatus#RUNNING_EXECUTION} 除了{@link CommandType#RECOVER_TOLERANCE_FAULT_PROCESS}类型的Command{@link WorkflowFailoverCommandHandler}的状态不确认，可能对应全部的{@link WorkflowExecutionStatus}
     *
     * 按正常状态是 {@link WorkflowExecutionStatus#RUNNING_EXECUTION}，对应的action为{@link WorkflowRunningStateAction#startEventAction(IWorkflowExecutionRunnable, WorkflowStartLifecycleEvent)}
     *
     * 2、
     * @param workflowStateAction
     * @param workflowExecutionRunnable
     * @param workflowStartEvent
     */
    @Override
    public void handle(final IWorkflowStateAction workflowStateAction,
                       final IWorkflowExecutionRunnable workflowExecutionRunnable,
                       final WorkflowStartLifecycleEvent workflowStartEvent) {

        workflowStateAction.startEventAction(workflowExecutionRunnable, workflowStartEvent);
    }

    @Override
    public ILifecycleEventType matchEventType() {
        return WorkflowLifecycleEventType.START;
    }
}
