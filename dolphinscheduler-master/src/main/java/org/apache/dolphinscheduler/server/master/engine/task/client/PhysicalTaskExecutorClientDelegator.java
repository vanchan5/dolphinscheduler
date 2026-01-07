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

package org.apache.dolphinscheduler.server.master.engine.task.client;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.extract.base.client.Clients;
import org.apache.dolphinscheduler.extract.base.utils.Host;
import org.apache.dolphinscheduler.extract.worker.IPhysicalTaskExecutorOperator;
import org.apache.dolphinscheduler.plugin.registry.zookeeper.ZookeeperTreeCacheListenerAdapter;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.server.master.cluster.ClusterStateMonitors;
import org.apache.dolphinscheduler.server.master.cluster.loadbalancer.IWorkerLoadBalancer;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.TaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.exception.dispatch.TaskDispatchException;
import org.apache.dolphinscheduler.task.executor.eventbus.ITaskExecutorLifecycleEventReporter;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorDispatchRequest;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorDispatchResponse;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorKillRequest;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorKillResponse;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorPauseRequest;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorPauseResponse;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorReassignMasterRequest;
import org.apache.dolphinscheduler.task.executor.operations.TaskExecutorReassignMasterResponse;

import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

import org.apache.hadoop.ipc.RPC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PhysicalTaskExecutorClientDelegator implements ITaskExecutorClientDelegator {

    @Autowired
    private MasterConfig masterConfig;

    @Autowired
    private IWorkerLoadBalancer workerLoadBalancer;

    @Override
    public void dispatch(final ITaskExecutionRunnable taskExecutionRunnable) throws TaskDispatchException {
        final TaskExecutionContext taskExecutionContext = taskExecutionRunnable.getTaskExecutionContext();
        final String taskName = taskExecutionContext.getTaskName();
        final String physicalTaskExecutorAddress = workerLoadBalancer
                .select(taskExecutionContext.getWorkerGroup())
                .map(Host::of)
                .map(Host::getAddress)
                .orElseThrow(() -> new TaskDispatchException(
                        String.format("Cannot find the host to dispatch Task[id=%s, name=%s, workerGroup=%s]",
                                taskExecutionContext.getTaskInstanceId(), taskName,
                                taskExecutionContext.getWorkerGroup())));

        taskExecutionContext.setHost(physicalTaskExecutorAddress);
        taskExecutionRunnable.getTaskInstance().setHost(physicalTaskExecutorAddress);

        try {
            final TaskExecutorDispatchResponse taskExecutorDispatchResponse = Clients
                    .withService(IPhysicalTaskExecutorOperator.class)
                    .withHost(physicalTaskExecutorAddress)
                    .dispatchTask(TaskExecutorDispatchRequest.of(taskExecutionRunnable.getTaskExecutionContext()));
            if (!taskExecutorDispatchResponse.isDispatchSuccess()) {
                throw new TaskDispatchException(
                        "Dispatch task: " + taskName + " to " + physicalTaskExecutorAddress + " failed: "
                                + taskExecutorDispatchResponse);
            }
        } catch (TaskDispatchException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskDispatchException(
                    "Dispatch task: " + taskName + " to " + physicalTaskExecutorAddress + " failed", e);
        }
    }

    /**
     * worker故障触发
     *   监听 {@link ZookeeperTreeCacheListenerAdapter#childEvent(CuratorFramework, TreeCacheEvent)}
     *       {@link ClusterStateMonitors#start()}
     *
     * @param taskExecutionRunnable
     * @return
     */
    @Override
    public boolean reassignMasterHost(final ITaskExecutionRunnable taskExecutionRunnable) {
        final String taskName = taskExecutionRunnable.getName();
        checkArgument(taskExecutionRunnable.isTaskInstanceInitialized(),
                "Task " + taskName + "is not initialized cannot take-over");

        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        final String taskExecutorHost = taskInstance.getHost();
        if (StringUtils.isEmpty(taskExecutorHost)) {
            log.debug(
                    "The task executor: {} host is empty, cannot take-over, this might caused by the task hasn't dispatched",
                    taskName);
            return false;
        }

        /**
         * 统一处理逻辑，同时覆盖：
         * 纯 Worker 故障场景（Master 未故障）
         * Master 故障转移 + Worker 故障检测的复合场景
         *
         * Worker 故障转移本身不涉及 Master 故障，但可能发生在 Master 故障转移之后，需要更新 Worker 上的 workflowInstanceHost。
         * 即使 Master 没有故障，更新操作也是安全的，且能处理 Master 故障转移后的遗留问题
         */
        final TaskExecutorReassignMasterRequest taskExecutorReassignMasterRequest =
                TaskExecutorReassignMasterRequest.builder()
                        .taskInstanceId(taskInstance.getId())
                        .workflowHost(masterConfig.getMasterAddress()) // 当前处理故障的master地址
                        .build();
        // 需要告诉worker服务任务回报的master服务地址,调用RPC接口
        final TaskExecutorReassignMasterResponse taskExecutorReassignMasterResponse =
                Clients
                        .withService(IPhysicalTaskExecutorOperator.class)
                        .withHost(taskInstance.getHost())
                        .reassignWorkflowInstanceHost(taskExecutorReassignMasterRequest);
        /**
         * RPC 调用,确认worker是否可达
         * 返回 false，创建新的故障转移任务实例 {@link TaskExecutionRunnable#failover()},创建新的taskInstance
         *            不是其他 Worker 主动“接管”，而是 Master 重新分派任务到其他 Worker {@link PhysicalTaskExecutorClientDelegator#dispatch(ITaskExecutionRunnable)}
         *            故障 Worker 会被自动排除（从注册中心移除或状态非 NORMAL）
         *            通过负载均衡器从正常 Worker 中选择一个进行分派
         *            新任务实例的 host 字段会被更新为选中的 Worker 地址
         * 返回 true, 说明任务被worker接管
         */
        boolean success = taskExecutorReassignMasterResponse.isSuccess();
        if (success) {
            log.info("Reassign master host {} to {} successfully", taskExecutorHost, taskName);
        } else {
            log.info("Reassign master host {} on {} failed with response {}",
                    taskExecutorHost,
                    taskName,
                    taskExecutorReassignMasterResponse);
        }
        return success;
    }

    @Override
    public void pause(final ITaskExecutionRunnable taskExecutionRunnable) {
        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        final String executorHost = taskInstance.getHost();
        final String taskName = taskInstance.getName();
        checkArgument(StringUtils.isNotEmpty(executorHost), "Executor host is empty");

        final TaskExecutorPauseResponse pauseResponse = Clients
                .withService(IPhysicalTaskExecutorOperator.class)
                .withHost(taskInstance.getHost())
                .pauseTask(TaskExecutorPauseRequest.of(taskInstance.getId()));
        if (pauseResponse.isSuccess()) {
            log.info("Pause task {} on executor {} successfully", taskName, executorHost);
        } else {
            log.warn("Pause task {} on executor {} failed with response {}", taskName, executorHost, pauseResponse);
        }
    }

    @Override
    public void kill(final ITaskExecutionRunnable taskExecutionRunnable) {
        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        final String executorHost = taskInstance.getHost();
        final String taskName = taskInstance.getName();
        checkArgument(StringUtils.isNotEmpty(executorHost), "Executor host is empty");

        final TaskExecutorKillResponse killResponse = Clients
                .withService(IPhysicalTaskExecutorOperator.class)
                .withHost(executorHost)
                .killTask(TaskExecutorKillRequest.of(taskInstance.getId()));
        if (killResponse.isSuccess()) {
            log.info("Kill task {} on executor {} successfully", taskName, executorHost);
        } else {
            log.warn("Kill task {} on executor {} failed with response {}", taskName, executorHost, killResponse);
        }
    }

    @Override
    public void ackTaskExecutorLifecycleEvent(final ITaskExecutionRunnable taskExecutionRunnable,
                                              final ITaskExecutorLifecycleEventReporter.TaskExecutorLifecycleEventAck taskExecutorLifecycleEventAck) {
        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        final String executorHost = taskInstance.getHost();
        checkArgument(StringUtils.isNotEmpty(executorHost), "Executor host is empty");

        Clients
                .withService(IPhysicalTaskExecutorOperator.class)
                .withHost(executorHost)
                .ackPhysicalTaskExecutorLifecycleEvent(taskExecutorLifecycleEventAck);
    }

}
