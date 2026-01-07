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
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.server.master.cluster.ClusterStateMonitors;
import org.apache.dolphinscheduler.server.master.cluster.MasterServerMetadata;
import org.apache.dolphinscheduler.server.master.engine.AbstractLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.ILifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBusFireWorker;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBusFireWorkers;
import org.apache.dolphinscheduler.server.master.engine.command.CommandEngine;
import org.apache.dolphinscheduler.server.master.engine.command.handler.AbstractCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.command.handler.WorkflowFailoverCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.system.event.MasterFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.system.event.MasterFailoverEventHandler;
import org.apache.dolphinscheduler.server.master.engine.system.event.WorkerFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.system.event.WorkerFailoverEventHandler;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.TaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.WorkflowLifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.event.WorkflowStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.WorkflowExecutionRunnableFactory;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.*;

import lombok.extern.slf4j.Slf4j;

import org.apache.dolphinscheduler.server.master.failover.FailoverCoordinator;
import org.apache.dolphinscheduler.server.master.failover.WorkflowFailover;
import org.apache.dolphinscheduler.server.master.runner.WorkflowExecuteContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowStartLifecycleEventHandler
        extends
            AbstractWorkflowLifecycleEventHandler<WorkflowStartLifecycleEvent> {

    /**
     * 事件:{@link WorkflowStartLifecycleEvent}
     * 1、工作流事件以及工作流执行状态
     * 工作流开始事件{@link WorkflowStartLifecycleEvent#of(IWorkflowExecutionRunnable)} 只有一个触发点 {@link CommandEngine#bootstrapWorkflowExecutionRunnable(IWorkflowExecutionRunnable)}
     * 此时经过组装{@link WorkflowExecutionRunnableFactory#createWorkflowExecuteRunnable(Command)}
     *          {@link AbstractCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
     * 后大多数状态为 {@link WorkflowExecutionStatus#RUNNING_EXECUTION} 除了{@link CommandType#RECOVER_TOLERANCE_FAULT_PROCESS}类型的Command{@link WorkflowFailoverCommandHandler}的状态不确认，可能对应全部的{@link WorkflowExecutionStatus}
     *
     * 按正常状态是 {@link WorkflowExecutionStatus#RUNNING_EXECUTION}，对应的action为{@link WorkflowRunningStateAction#startEventAction(IWorkflowExecutionRunnable, WorkflowStartLifecycleEvent)}
     *
     * 2、处理master故障转移工作流
     *   2.1 触发点
     *        {@link ClusterStateMonitors#masterRemoved(MasterServerMetadata)}
     *        {@link MasterFailoverEventHandler#handle(MasterFailoverEvent)}
     *        {@link FailoverCoordinator#failoverMaster(MasterFailoverEvent)}
     *        {@link WorkflowFailover#failoverWorkflow(WorkflowInstance)}
     *      创建新的Command,CommandType.RECOVER_TOLERANCE_FAULT_PROCESS
     *      更新旧的WorkflowInstanceState = {@link WorkflowExecutionStatus#FAILOVER}
     *   2.2 拉取该Command
     *      2.2.1 构建
     *          {@link AbstractCommandHandler#handleCommand(Command)}
     *          {@link WorkflowFailoverCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
     *          更新WorkflowInstanceState状态为 容错的时候的workflowInstance状态
     *      2.2.2 发布
     *          {@link WorkflowFailoverCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
     *          {@link CommandEngine#bootstrapWorkflowExecutionRunnable(IWorkflowExecutionRunnable)}
     *          {@link WorkflowStartLifecycleEvent}
     *   2.3 handle处理
     *      {@link WorkflowEventBusFireWorkers#start()}
     *      {@link WorkflowEventBusFireWorker#doFireSingleEvent(IWorkflowExecutionRunnable, AbstractLifecycleEvent)}
     *
     *  2.4、IWorkflowStateAction
     *       {@link WorkflowExecutionStatus}状态不确定,根据状态选择对应的IWorkflowStateAction
     *       RUNNING_EXECUTION → {@link WorkflowRunningStateAction}（会触发任务）
     *       READY_PAUSE → {@link WorkflowReadyPauseStateAction}（会触发任务）
     *       READY_STOP → {@link WorkflowReadyStopStateAction}（会触发任务）
     *       PAUSE → {@link WorkflowPausedStateAction}（不会触发任务，只记录警告）
     *       FAILURE → {@link WorkflowFailedStateAction}（不会触发任务，只记录警告）
     *       SUCCESS → {@link WorkflowSuccessStateAction}（不会触发任务，只记录警告）
     *       FAILOVER → {@link WorkflowFailoverStateAction}（不会触发任务，只记录警告）
     *
     *          如果工作流状态是 READY_PAUSE 或 READY_STOP，触发 triggerTasks() 的主要目的是：
     *          最终调用 {@link TaskExecutionRunnable#takeOverTaskFromExecutor()}
     *          对于正在运行的任务（RUNNING_EXECUTION 或 DISPATCH）：
     *          更新 Worker 上的 workflowInstanceHost，确保事件发送到新的 Master
     *          这是必要的，即使工作流处于暂停/停止状态
     *          对于未执行的任务（SUBMITTED_SUCCESS）：
     *          会被直接暂停或杀死，不会真正执行
     *          这部分触发可能显得多余，但逻辑上统一处理
     *
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
