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

package org.apache.dolphinscheduler.server.master.failover;

import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.dao.repository.WorkflowInstanceDao;
import org.apache.dolphinscheduler.plugin.task.api.enums.TaskExecutionStatus;
import org.apache.dolphinscheduler.registry.api.RegistryClient;
import org.apache.dolphinscheduler.registry.api.enums.RegistryNodeType;
import org.apache.dolphinscheduler.registry.api.utils.RegistryUtils;
import org.apache.dolphinscheduler.server.master.cluster.ClusterManager;
import org.apache.dolphinscheduler.server.master.cluster.MasterServerMetadata;
import org.apache.dolphinscheduler.server.master.cluster.WorkerServerMetadata;
import org.apache.dolphinscheduler.server.master.engine.IWorkflowRepository;
import org.apache.dolphinscheduler.server.master.engine.system.event.GlobalMasterFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.system.event.MasterFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.system.event.WorkerFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;

import org.apache.commons.lang3.time.StopWatch;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 故障转移协调器
 * <p>
 * 负责在 Master 或 Worker 服务器从集群中移除时进行故障转移，确保系统在服务器故障后能够继续正常工作。
 * <p>
 * 主要功能：
 * <ul>
 *   <li>全局 Master 故障转移：在服务器首次启动时，扫描整个系统中需要故障转移的工作流并执行转移</li>
 *   <li>单个 Master 故障转移：当检测到某个 Master 服务器崩溃时，转移该 Master 负责的所有工作流</li>
 *   <li>Worker 故障转移：当检测到某个 Worker 服务器崩溃时，转移该 Worker 正在执行的所有任务</li>
 * </ul>
 * <p>
 * 故障转移机制：
 * <ul>
 *   <li>使用分布式锁避免多个 Master 同时执行故障转移</li>
 *   <li>通过注册中心记录故障转移状态，避免重复转移</li>
 *   <li>根据服务器启动时间或事件时间确定故障转移截止时间，只转移在截止时间之前启动的工作流/任务</li>
 * </ul>
 */
@Slf4j
@Component
public class FailoverCoordinator implements IFailoverCoordinator {

    /** 注册中心客户端，用于获取分布式锁和持久化故障转移状态 */
    @Autowired
    private RegistryClient registryClient;

    /** 集群管理器，用于获取集群中 Master 和 Worker 的元数据信息 */
    @Autowired
    private ClusterManager clusterManager;

    /** 工作流仓库，用于获取当前内存中运行的工作流实例 */
    @Autowired
    private IWorkflowRepository workflowRepository;

    /** 任务故障转移处理器，负责执行具体的任务故障转移逻辑 */
    @Autowired
    private TaskFailover taskFailover;

    /** 工作流实例数据访问对象，用于查询数据库中需要故障转移的工作流 */
    @Autowired
    private WorkflowInstanceDao workflowInstanceDao;

    /** 工作流故障转移处理器，负责执行具体的工作流故障转移逻辑 */
    @Autowired
    private WorkflowFailover workflowFailover;

    /**
     * 全局 Master 故障转移
     * <p>
     * 在服务器首次启动时调用，扫描整个系统中所有需要故障转移的工作流并执行转移。
     * 此方法执行较慢，因为需要扫描整个系统的工作流，不应该在主线程中调用。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>查询数据库中所有包含未完成工作流的 Master 地址</li>
     *   <li>对每个 Master 地址，检查该 Master 是否仍然存活</li>
     *   <li>如果 Master 存活：使用该 Master 的启动时间作为故障转移截止时间</li>
     *   <li>如果 Master 不存活：使用事件时间作为故障转移截止时间</li>
     *   <li>执行故障转移，只转移在截止时间之前启动的工作流</li>
     * </ol>
     *
     * @param globalMasterFailoverEvent 全局 Master 故障转移事件
     */
    @Override
    public void globalMasterFailover(final GlobalMasterFailoverEvent globalMasterFailoverEvent) {
        final StopWatch failoverTimeCost = StopWatch.createStarted();
        log.info("Global master failover starting");
        // 查询数据库中所有包含未完成工作流的 Master 地址列表
        final List<String> masterAddressWhichContainsUnFinishedWorkflow =
                workflowInstanceDao.queryNeedFailoverMasters();
        // 遍历每个需要故障转移的 Master 地址
        for (final String masterAddress : masterAddressWhichContainsUnFinishedWorkflow) {
            // 检查该 Master 是否仍然存活（可能重新连接到了注册中心）
            final Optional<MasterServerMetadata> aliveMasterOptional =
                    clusterManager.getMasterClusters().getServer(masterAddress);
            if (aliveMasterOptional.isPresent()) {
                // 如果 Master 仍然存活，使用该 Master 的启动时间作为故障转移截止时间
                // 这样可以避免转移该 Master 重新启动后新启动的工作流
                final MasterServerMetadata aliveMasterServerMetadata = aliveMasterOptional.get();
                log.info("The master[{}] is alive, do global master failover on it", aliveMasterServerMetadata);
                doMasterFailover(
                        masterAddress,
                        aliveMasterServerMetadata.getServerStartupTime(),
                        RegistryUtils.getFailoveredNodePathWhichStartupTimeIsUnknown(
                                masterAddress));
            } else {
                // 如果 Master 不存活，使用事件时间作为故障转移截止时间
                // 这样可以确保转移所有在该时间点之前启动的工作流
                log.info("The master[{}] is not alive, do global master failover on it", masterAddress);
                doMasterFailover(
                        masterAddress,
                        globalMasterFailoverEvent.getEventTime().getTime(),
                        RegistryUtils.getFailoveredNodePathWhichStartupTimeIsUnknown(masterAddress));
            }
        }

        failoverTimeCost.stop();
        log.info("Global master failover finished, cost: {}/ms", failoverTimeCost.getTime());
    }

    /**
     * 单个 Master 故障转移
     * <p>
     * 当检测到某个 Master 服务器从集群中移除时调用，转移该 Master 负责的所有工作流。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>检查该 Master 是否仍然存活（可能重新连接到了注册中心）</li>
     *   <li>如果 Master 存活且启动时间相同，说明是误报，跳过故障转移</li>
     *   <li>如果 Master 不存活或启动时间不同，执行故障转移</li>
     *   <li>使用事件时间作为故障转移截止时间，只转移在截止时间之前启动的工作流</li>
     * </ol>
     *
     * @param masterFailoverEvent Master 故障转移事件，包含故障 Master 的元数据信息
     */
    @Override
    public void failoverMaster(final MasterFailoverEvent masterFailoverEvent) {
        final MasterServerMetadata masterServerMetadata = masterFailoverEvent.getMasterServerMetadata();
        log.info("Master[{}] failover starting", masterServerMetadata);
        final String masterAddress = masterServerMetadata.getAddress();

        // 检查该 Master 是否仍然存活（可能重新连接到了注册中心）
        final Optional<MasterServerMetadata> aliveMasterOptional =
                clusterManager.getMasterClusters().getServer(masterAddress);
        if (aliveMasterOptional.isPresent()) {
            final MasterServerMetadata aliveMasterServerMetadata = aliveMasterOptional.get();
            // 如果 Master 存活且启动时间相同，说明是误报（可能是网络抖动导致临时断开）
            // 跳过故障转移，避免不必要的转移操作
            if (aliveMasterServerMetadata.getServerStartupTime() == masterServerMetadata.getServerStartupTime()) {
                log.info("The master[{}] is alive, maybe it reconnect to registry skip failover", masterServerMetadata);
                return;
            }
        }
        // 执行故障转移，使用事件时间作为截止时间
        doMasterFailover(
                masterServerMetadata.getAddress(),
                masterFailoverEvent.getEventTime().getTime(),
                RegistryUtils.getFailoveredNodePath(
                        masterServerMetadata.getAddress(),
                        masterServerMetadata.getServerStartupTime(),
                        masterServerMetadata.getProcessId()));
    }

    /**
     * 执行 Master 故障转移
     * <p>
     * 转移由指定 Master 调度且启动时间在截止时间之前的工作流。
     * <p>
     * 故障转移机制说明：
     * <ul>
     *   <li>使用分布式锁避免多个 Master 同时执行故障转移，确保同一时间只有一个 Master 在处理</li>
     *   <li>一旦工作流被故障转移，其状态会变为 FAILOVER</li>
     *   <li>FAILOVER 状态的工作流重新触发后，其 host 会变更为新的 Master，并拥有新的启动时间</li>
     *   <li>因此，即使一个 Master 被多次故障转移，也不会有问题（已转移的工作流不会再次被转移）</li>
     * </ul>
     *
     * @param masterAddress Master 服务器地址
     * @param workflowFailoverDeadline 工作流故障转移截止时间（毫秒时间戳）
     *                                  只转移启动时间在此时间之前的工作流
     * @param masterFailoverNodePath 注册中心中记录该 Master 故障转移状态的节点路径
     */
    private void doMasterFailover(final String masterAddress,
                                  final long workflowFailoverDeadline,
                                  final String masterFailoverNodePath) {
        final StopWatch failoverTimeCost = StopWatch.createStarted();
        // 获取分布式锁，避免多个 Master 同时执行故障转移
        registryClient.getLock(RegistryUtils.getMasterFailoverLockPath(masterAddress));
        try {
            // 检查该 Master 是否已经被故障转移过
            // 如果注册中心节点存在且存储的截止时间相同，说明已经转移过，跳过本次转移
            if (registryClient.exists(masterFailoverNodePath)
                    && String.valueOf(workflowFailoverDeadline).equals(registryClient.get(masterFailoverNodePath))) {
                log.error("The master[{}/{}] is exist at: {}, means it has already been failovered, skip failover",
                        masterAddress,
                        workflowFailoverDeadline,
                        masterFailoverNodePath);
                return;
            }
            // 获取需要故障转移的工作流列表（启动时间在截止时间之前的工作流）
            final List<WorkflowInstance> needFailoverWorkflows =
                    getFailoverWorkflowsForMaster(masterAddress, new Date(workflowFailoverDeadline));
            // 对每个工作流执行故障转移
            needFailoverWorkflows.forEach(workflowFailover::failoverWorkflow);
            // 在注册中心持久化故障转移状态，记录截止时间，用于后续判断是否已转移
            registryClient.persist(masterFailoverNodePath, String.valueOf(workflowFailoverDeadline));
            failoverTimeCost.stop();
            log.info("Master[{}] failover {} workflows finished, cost: {}/ms",
                    masterAddress,
                    needFailoverWorkflows.size(),
                    failoverTimeCost.getTime());
        } finally {
            // 释放分布式锁
            registryClient.releaseLock(RegistryNodeType.MASTER_FAILOVER_LOCK.getRegistryPath());
        }
    }

    /**
     * 获取指定 Master 需要故障转移的工作流列表
     * <p>
     * 筛选条件：
     * <ol>
     *   <li>工作流不在当前内存中（避免重复转移）</li>
     *   <li>工作流的启动时间或重启时间在 Master 崩溃时间之前</li>
     * </ol>
     *
     * @param masterAddress Master 服务器地址
     * @param masterCrashTime Master 崩溃时间，用于判断哪些工作流需要转移
     * @return 需要故障转移的工作流实例列表
     */
    private List<WorkflowInstance> getFailoverWorkflowsForMaster(final String masterAddress,
                                                                 final Date masterCrashTime) {
        // 查询数据库中该 Master 负责的所有需要故障转移的工作流实例
        final List<WorkflowInstance> workflowInstances =
                workflowInstanceDao.queryNeedFailoverWorkflowInstances(masterAddress);
        return workflowInstances.stream()
                .filter(workflowInstance -> {
                    /**
                     * 同步机制说明：
                     * 
                     * 1. Command 的唯一性保证：
                     *    - 当工作流被故障转移时，会插入 RECOVER_TOLERANCE_FAULT_PROCESS 类型的 Command 到数据库
                     *    - 所有 Master 的 CommandEngine 都会从数据库读取 Command 并尝试处理
                     *    - 在 WorkflowExecutionRunnableFactory.createWorkflowExecuteRunnable() 中，使用 @Transactional 注解
                     *    - 处理 Command 之前，会先删除数据库中的 Command（deleteCommandOrThrow()）
                     *    - 如果删除失败（说明其他 Master 已经删除了），会抛出 CommandDuplicateHandleException
                     *    - 这确保了每个 Command 只会被一个 Master 处理，避免重复处理
                     * 
                     * 2. 工作流 host 更新和内存同步：
                     *    - 当某个 Master 成功处理 Command 时：
                     *      a. 在 ProcessServiceImpl 中更新工作流的 host 字段为当前 Master 的地址
                     *      b. 创建 WorkflowExecutionRunnable 并添加到 workflowRepository（CommandEngine.bootstrapWorkflowExecutionRunnable()）
                     *    - 工作流被添加到处理它的 Master 的内存中（workflowRepository）
                     * 
                     * 3. 故障转移时的检查：
                     *    - workflowRepository.contains(workflowInstance.getId()) 检查的是当前 Master 的内存
                     *    - 如果返回 true：说明当前 Master 已经处理了恢复 Command，工作流已经在当前 Master 的内存中运行
                     *    - 如果返回 false：说明当前 Master 还没有处理这个 Command（可能被其他 Master 处理了，或者还没有被处理）
                     *    - 由于 Command 的唯一性保证，即使其他 Master 已经处理了 Command，当前 Master 也不会重复处理
                     * 
                     * 4. 为什么只检查内存而不检查数据库 host？
                     *    - 因为 Command 处理是异步的，可能存在时间差
                     *    - 如果其他 Master 已经处理了 Command 并更新了 host，但当前 Master 还没有处理，检查 host 可能会误判
                     *    - 检查内存更准确：如果工作流在当前 Master 的内存中，说明当前 Master 已经接管了它
                     *    - 如果工作流不在内存中，即使 host 指向当前 Master，也可能是旧数据，需要重新处理
                     *
                     * 如果工作流已经在当前内存中运行，说明已经被当前 Master 接管，不需要转移
                     */
                    if (workflowRepository.contains(workflowInstance.getId())) {
                        return false;
                    }

                    // 检查工作流的重启时间（如果存在）
                    final Date restartTime = workflowInstance.getRestartTime();
                    if (restartTime != null) {
                        // 如果重启时间在 Master 崩溃时间之前，需要转移
                        return restartTime.before(masterCrashTime);
                    }

                    // 如果没有重启时间，检查启动时间
                    // 如果启动时间在 Master 崩溃时间之前，需要转移
                    final Date startTime = workflowInstance.getStartTime();
                    return startTime.before(masterCrashTime);
                })
                .collect(Collectors.toList());
    }

    /**
     * Worker 故障转移
     * <p>
     * 当检测到某个 Worker 服务器从集群中移除时调用，转移该 Worker 正在执行的所有任务。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>检查该 Worker 是否仍然存活（可能重新连接到了注册中心）</li>
     *   <li>如果 Worker 存活且启动时间相同，说明是误报，跳过故障转移</li>
     *   <li>如果 Worker 不存活或启动时间不同，执行故障转移</li>
     *   <li>使用当前时间作为故障转移截止时间，只转移在截止时间之前提交的任务</li>
     * </ol>
     *
     * @param workerFailoverEvent Worker 故障转移事件，包含故障 Worker 的元数据信息
     */
    @Override
    public void failoverWorker(final WorkerFailoverEvent workerFailoverEvent) {
        final WorkerServerMetadata workerServerMetadata = workerFailoverEvent.getWorkerServerMetadata();
        log.info("Worker[{}] failover starting", workerServerMetadata);

        // 检查该 Worker 是否仍然存活（可能重新连接到了注册中心）
        final Optional<WorkerServerMetadata> aliveWorkerOptional =
                clusterManager.getWorkerClusters().getServer(workerServerMetadata.getAddress());
        if (aliveWorkerOptional.isPresent()) {
            final WorkerServerMetadata aliveWorkerServerMetadata = aliveWorkerOptional.get();
            // 如果 Worker 存活且启动时间相同，说明是误报（可能是网络抖动导致临时断开）
            // 跳过故障转移，避免不必要的转移操作
            if (aliveWorkerServerMetadata.getServerStartupTime() == workerServerMetadata.getServerStartupTime()) {
                log.info("The worker[{}] is alive, maybe it reconnect to registry skip failover", workerServerMetadata);
                return;
            }
        }
        // 执行故障转移，使用当前时间作为截止时间
        doWorkerFailover(
                workerServerMetadata.getAddress(),
                System.currentTimeMillis(),
                RegistryUtils.getFailoveredNodePath(
                        workerServerMetadata.getAddress(),
                        workerServerMetadata.getServerStartupTime(),
                        workerServerMetadata.getProcessId()));
    }

    /**
     * 执行 Worker 故障转移
     * <p>
     * 转移已分派到指定 Worker 且提交时间在截止时间之前的任务。
     * <p>
     * 注意：不检查 workerFailoverNodePath 是否存在，因为同一个 Worker 可能被多个 Master 故障转移
     * （不同 Master 可能同时检测到同一个 Worker 的故障）
     *
     * @param workerAddress Worker 服务器地址
     * @param taskFailoverDeadline 任务故障转移截止时间（毫秒时间戳）
     *                             只转移提交时间在此时间之前的任务
     * @param workerFailoverNodePath 注册中心中记录该 Worker 故障转移状态的节点路径
     */
    private void doWorkerFailover(final String workerAddress,
                                  final long taskFailoverDeadline,
                                  final String workerFailoverNodePath) {
        final StopWatch failoverTimeCost = StopWatch.createStarted();
        // 注意：不检查 workerFailoverNodePath 是否存在，因为同一个 Worker 可能被多个 Master 故障转移
        // 不同 Master 可能同时检测到同一个 Worker 的故障，都需要执行转移

        // 获取需要故障转移的任务列表（提交时间在截止时间之前的任务）
        final List<ITaskExecutionRunnable> needFailoverTasks =
                getFailoverTaskForWorker(workerAddress, new Date(taskFailoverDeadline));
        // 对每个任务执行故障转移
        needFailoverTasks.forEach(taskFailover::failoverTask);

        // 在注册中心持久化故障转移状态，记录当前时间
        registryClient.persist(
                workerFailoverNodePath,
                String.valueOf(System.currentTimeMillis()));
        failoverTimeCost.stop();
        log.info("Worker[{}] failover {} tasks finished, cost: {}/ms",
                workerAddress,
                needFailoverTasks.size(),
                failoverTimeCost.getTime());
    }

    /**
     * 获取指定 Worker 需要故障转移的任务列表
     * <p>
     * 从当前内存中所有运行的工作流中筛选出需要转移的任务。
     * <p>
     * 筛选条件：
     * <ol>
     *   <li>任务实例已初始化</li>
     *   <li>任务的主机地址匹配指定的 Worker 地址</li>
     *   <li>任务状态为 DISPATCH（已分派）或 RUNNING_EXECUTION（正在执行）</li>
     *   <li>任务的提交时间在故障转移截止时间之前</li>
     * </ol>
     *
     * @param workerAddress Worker 服务器地址
     * @param taskFailoverDeadline 任务故障转移截止时间，用于判断哪些任务需要转移
     * @return 需要故障转移的任务执行 Runnable 列表
     */
    private List<ITaskExecutionRunnable> getFailoverTaskForWorker(final String workerAddress,
                                                                  final Date taskFailoverDeadline) {
        return workflowRepository.getAll()
                .stream()
                // 获取每个工作流的执行图
                .map(IWorkflowExecutionRunnable::getWorkflowExecutionGraph)
                // 扁平化处理，获取所有活跃的任务执行 Runnable
                .flatMap(workflowExecutionGraph -> workflowExecutionGraph.getActiveTaskExecutionRunnable().stream())
                // 过滤：只保留已初始化的任务实例
                .filter(ITaskExecutionRunnable::isTaskInstanceInitialized)
                // 过滤：只保留主机地址匹配的任务（即分派到该 Worker 的任务）
                .filter(taskExecutionRunnable -> workerAddress
                        .equals(taskExecutionRunnable.getTaskInstance().getHost()))
                // 过滤：只保留状态为 DISPATCH（已分派）或 RUNNING_EXECUTION（正在执行）的任务
                // 这些状态的任务需要故障转移，已完成或失败的任务不需要转移
                .filter(taskExecutionRunnable -> {
                    final TaskExecutionStatus state = taskExecutionRunnable.getTaskInstance().getState();
                    return state == TaskExecutionStatus.DISPATCH || state == TaskExecutionStatus.RUNNING_EXECUTION;
                })
                // 过滤：只保留提交时间在截止时间之前的任务
                // 注意：submitTime 不应该为 null，除非有人手动设置为 null（这是异常情况）
                .filter(taskExecutionRunnable -> {
                    final Date submitTime = taskExecutionRunnable.getTaskInstance().getSubmitTime();
                    return submitTime != null && submitTime.before(taskFailoverDeadline);
                })
                .collect(Collectors.toList());
    }

}
