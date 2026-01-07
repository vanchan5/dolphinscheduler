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

package org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event;

import org.apache.dolphinscheduler.server.master.engine.ILifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.AbstractTaskLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.TaskLifecycleEventType;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler.TaskStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.event.WorkflowStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler.WorkflowStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.AbstractWorkflowStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.IWorkflowStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.WorkflowRunningStateAction;

import java.util.List;


/**
 * 任务开始触发点:
 * {@link WorkflowStartLifecycleEventHandler#handle(IWorkflowStateAction, IWorkflowExecutionRunnable, WorkflowStartLifecycleEvent)} ->
 * {@link WorkflowRunningStateAction#startEventAction(IWorkflowExecutionRunnable, WorkflowStartLifecycleEvent)} ->
 * {@link AbstractWorkflowStateAction#triggerTasks(IWorkflowExecutionRunnable, List)}
 */
@Getter
@AllArgsConstructor
public class TaskStartLifecycleEvent extends AbstractTaskLifecycleEvent {

    private final ITaskExecutionRunnable taskExecutionRunnable;

    /**
     * {@link TaskStartLifecycleEventHandler}
     * @param taskExecutionRunnable
     * @return
     */
    public static TaskStartLifecycleEvent of(ITaskExecutionRunnable taskExecutionRunnable) {
        return new TaskStartLifecycleEvent(taskExecutionRunnable);
    }

    /**
     * 处理器 {@link TaskStartLifecycleEventHandler}
     * @return
     */
    @Override
    public ILifecycleEventType getEventType() {
        return TaskLifecycleEventType.START;
    }

    @Override
    public String toString() {
        return "TaskStartLifecycleEvent{" +
                "task=" + taskExecutionRunnable.getName() +
                '}';
    }
}
