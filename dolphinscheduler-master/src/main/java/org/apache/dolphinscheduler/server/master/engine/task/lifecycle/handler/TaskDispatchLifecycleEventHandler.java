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

package org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler;

import org.apache.dolphinscheduler.plugin.task.api.enums.TaskExecutionStatus;
import org.apache.dolphinscheduler.server.master.engine.ILifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.task.client.ITaskExecutorClientDelegator;
import org.apache.dolphinscheduler.server.master.engine.task.client.TaskExecutorClient;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.TaskLifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskDispatchLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.task.statemachine.AbstractTaskStateAction;
import org.apache.dolphinscheduler.server.master.engine.task.statemachine.ITaskStateAction;
import org.apache.dolphinscheduler.server.master.engine.task.statemachine.TaskSubmittedStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;

import lombok.extern.slf4j.Slf4j;

import org.apache.dolphinscheduler.server.master.runner.GlobalTaskDispatchWaitingQueue;
import org.apache.dolphinscheduler.server.master.runner.GlobalTaskDispatchWaitingQueueLooper;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TaskDispatchLifecycleEventHandler extends AbstractTaskLifecycleEventHandler<TaskDispatchLifecycleEvent> {

    /**
     * 1、任务状态
     *  任务分发事件从从{@link AbstractTaskStateAction#tryToDispatchTask(ITaskExecutionRunnable)}触发
     *  此时任务执行状态还是 {@link TaskExecutionStatus#SUBMITTED_SUCCESS}
     *  所以ITaskStateAction对应的是
     *  {@link TaskSubmittedStateAction#dispatchEventAction(IWorkflowExecutionRunnable, ITaskExecutionRunnable, TaskDispatchLifecycleEvent)}
     * 2、RPC调用远程服务
     *  分发事件到{@link GlobalTaskDispatchWaitingQueue#dispatchTaskExecuteRunnableWithDelay(ITaskExecutionRunnable, long)}
     *  再由全局任务分发等待队列循环器{@link GlobalTaskDispatchWaitingQueueLooper#doDispatch()}调用任务执行客户端
     *  {@link TaskExecutorClient#dispatch(ITaskExecutionRunnable)}分发
     *  最终调用{@link ITaskExecutorClientDelegator#dispatch(ITaskExecutionRunnable)}执行远程调用
     *
     *
     * @param taskStateAction
     * @param workflowExecutionRunnable
     * @param taskExecutionRunnable
     * @param event
     */
    @Override
    public void handle(final ITaskStateAction taskStateAction,
                       final IWorkflowExecutionRunnable workflowExecutionRunnable,
                       final ITaskExecutionRunnable taskExecutionRunnable,
                       final TaskDispatchLifecycleEvent event) {
        taskStateAction.dispatchEventAction(workflowExecutionRunnable, taskExecutionRunnable, event);
    }

    @Override
    public ILifecycleEventType matchEventType() {
        return TaskLifecycleEventType.DISPATCH;
    }
}
