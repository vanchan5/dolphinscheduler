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

package org.apache.dolphinscheduler.server.master.engine.graph;

import org.apache.dolphinscheduler.common.enums.Flag;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.plugin.task.api.enums.TaskExecutionStatus;
import org.apache.dolphinscheduler.plugin.task.api.utils.TaskTypeUtils;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流执行图
 * <p>
 * WorkflowExecutionGraph表示运行时的真实DAG（有向无环图），它可能是工作流DAG的一个子图。
 * 与WorkflowGraph（基于WorkflowDefinition的静态图）不同，WorkflowExecutionGraph表示工作流实例的动态执行图，
 * 包含任务的实际执行状态和运行时信息。
 * </p>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>管理所有任务执行Runnable（ITaskExecutionRunnable）</li>
 *   <li>维护任务之间的依赖关系（predecessors和successors）</li>
 *   <li>跟踪任务的执行状态（active、inactive、failed、paused、killed、skipped）</li>
 *   <li>提供任务查询、状态检查、状态标记等方法</li>
 * </ul>
 * </p>
 * <p>
 * 构建过程：
 * <ol>
 *   <li>在RunWorkflowCommandHandler.assembleWorkflowExecutionGraph中创建</li>
 *   <li>通过WorkflowGraphTopologyLogicalVisitor遍历WorkflowGraph</li>
 *   <li>为每个任务节点创建TaskExecutionRunnable并调用addNode添加到图</li>
 *   <li>调用addEdge添加任务之间的依赖边</li>
 * </ol>
 * </p>
 *
 * @see IWorkflowExecutionGraph
 * @see WorkflowGraph
 * @see ITaskExecutionRunnable
 */
public class WorkflowExecutionGraph implements IWorkflowExecutionGraph {

    /**
     * 所有任务执行Runnable的映射表
     * <p>key: 任务名称（taskName），value: 任务执行Runnable实例</p>
     * <p>存储图中所有任务的执行Runnable，用于快速查找和访问</p>
     */
    private final Map<String, ITaskExecutionRunnable> totalTaskExecuteRunnableMap;

    /**
     * 失败的任务链集合
     * <p>存储已标记为失败状态的任务名称集合，用于判断工作流是否失败</p>
     */
    private final Set<String> failureTaskChains;

    /**
     * 暂停的任务链集合
     * <p>存储已标记为暂停状态的任务名称集合，用于判断工作流是否暂停</p>
     */
    private final Set<String> pausedTaskChains;

    /**
     * 被杀死的任务链集合
     * <p>存储已标记为杀死状态的任务名称集合，用于判断工作流是否被杀死</p>
     */
    private final Set<String> killedTaskChains;

    /**
     * 跳过的任务集合
     * <p>存储被跳过执行的任务名称集合，这些任务不会被执行</p>
     */
    private final Set<String> skippedTask;

    /**
     * 前置任务映射表
     * <p>key: 任务名称，value: 该任务的所有前置任务名称集合</p>
     * <p>用于查询任务的依赖关系和检查触发条件</p>
     */
    private final Map<String, Set<String>> predecessors;

    /**
     * 后继任务映射表
     * <p>key: 任务名称，value: 该任务的所有后继任务名称集合</p>
     * <p>用于查询任务的后续任务，触发后续任务的执行</p>
     */
    private final Map<String, Set<String>> successors;

    /**
     * 活跃任务执行Runnable集合
     * <p>存储当前正在执行中的任务名称集合，用于跟踪工作流的执行进度</p>
     * <p>当任务开始执行时，通过markTaskExecutionRunnableActive添加到集合</p>
     * <p>当任务执行完成时，通过markTaskExecutionRunnableInActive从集合移除</p>
     */
    private final Set<String> activeTaskExecutionRunnable;

    /**
     * 非活跃任务执行Runnable集合
     * <p>存储已完成执行的任务名称集合（已执行完成的任务）</p>
     * <p>当任务执行完成时，通过markTaskExecutionRunnableInActive添加到集合</p>
     */
    private final Set<String> inActiveTaskExecutionRunnable;

    /**
     * 构造函数
     * <p>初始化所有数据结构，创建空的Map和Set集合</p>
     */
    public WorkflowExecutionGraph() {
        this.failureTaskChains = new HashSet<>();
        this.pausedTaskChains = new HashSet<>();
        this.killedTaskChains = new HashSet<>();
        this.skippedTask = new HashSet<>();
        this.predecessors = new HashMap<>();
        this.successors = new HashMap<>();
        this.totalTaskExecuteRunnableMap = new HashMap<>();
        this.activeTaskExecutionRunnable = new HashSet<>();
        this.inActiveTaskExecutionRunnable = new HashSet<>();
    }

    /**
     * 添加任务节点到执行图
     * <p>
     * 将任务执行Runnable添加到图中，并初始化其前置和后继任务集合（初始为空）。
     * 此方法通常在构建WorkflowExecutionGraph时调用，在addEdge之前调用。
     * </p>
     *
     * @param taskExecutionRunnable 要添加的任务执行Runnable，不能为null
     */
    @Override
    public void addNode(final ITaskExecutionRunnable taskExecutionRunnable) {
        totalTaskExecuteRunnableMap.put(taskExecutionRunnable.getName(), taskExecutionRunnable);
        predecessors.computeIfAbsent(taskExecutionRunnable.getName(), k -> new HashSet<>());
        successors.computeIfAbsent(taskExecutionRunnable.getName(), k -> new HashSet<>());
    }

    /**
     * 添加任务依赖边到执行图
     * <p>
     * 在fromTaskName和toTaskNames之间建立依赖关系。
     * 此方法会同时更新successors和predecessors映射，保证数据一致性：
     * <ul>
     *   <li>将toTaskNames添加到fromTaskName的successors集合</li>
     *   <li>将fromTaskName添加到每个toTask的predecessors集合</li>
     * </ul>
     * </p>
     * <p>
     * 此方法通常在addNode之后调用，用于建立任务之间的依赖关系。
     * </p>
     *
     * @param fromTaskName 源任务名称（前置任务）
     * @param toTaskNames 目标任务名称集合（后继任务），不能为null
     */
    @Override
    public void addEdge(String fromTaskName, Set<String> toTaskNames) {
        successors.computeIfAbsent(fromTaskName, k -> new HashSet<>()).addAll(toTaskNames);
        toTaskNames.forEach(toTask -> predecessors.computeIfAbsent(toTask, k -> new HashSet<>()).add(fromTaskName));
    }

    /**
     * 获取起始节点列表
     * <p>
     * 返回所有前置任务为空的任务执行Runnable，这些任务可以立即开始执行。
     * 起始节点是工作流执行的入口点。
     * </p>
     *
     * @return 起始任务执行Runnable列表，如果没有起始节点则返回空列表
     */
    @Override
    public List<ITaskExecutionRunnable> getStartNodes() {
        return totalTaskExecuteRunnableMap.values()
                .stream()
                .filter(taskExecutionRunnable -> CollectionUtils
                        .isEmpty(predecessors.get(taskExecutionRunnable.getName())))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定任务的前置任务列表
     * <p>
     * 返回指定任务的所有前置任务（依赖的任务）的执行Runnable列表。
     * 用于检查任务的触发条件，判断是否可以开始执行该任务。
     * </p>
     *
     * @param taskName 任务名称
     * @return 前置任务执行Runnable列表，如果没有前置任务则返回空列表
     * @throws IllegalArgumentException 如果任务不存在于图中
     */
    @Override
    public List<ITaskExecutionRunnable> getPredecessors(final String taskName) {
        if (!predecessors.containsKey(taskName)) {
            throw new IllegalArgumentException("Cannot find the task: " + taskName + " in graph");
        }
        return predecessors
                .get(taskName)
                .stream()
                .map(this::getTaskExecutionRunnableByName)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定任务的后继任务列表
     * <p>
     * 返回指定任务的所有后继任务（依赖该任务的任务）的执行Runnable列表。
     * 用于在当前任务完成时触发后续任务的执行。
     * </p>
     *
     * @param taskName 任务名称
     * @return 后继任务执行Runnable列表，如果没有后继任务则返回空列表
     * @throws IllegalArgumentException 如果任务不存在于图中
     */
    @Override
    public List<ITaskExecutionRunnable> getSuccessors(final String taskName) {
        if (!successors.containsKey(taskName)) {
            throw new IllegalArgumentException("Cannot find the task code in graph");
        }
        return successors
                .get(taskName)
                .stream()
                .map(this::getTaskExecutionRunnableByName)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定任务执行Runnable的后继任务列表
     * <p>
     * 重载方法，通过任务执行Runnable获取后继任务列表。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 后继任务执行Runnable列表
     * @throws IllegalArgumentException 如果任务不存在于图中
     */
    @Override
    public List<ITaskExecutionRunnable> getSuccessors(final ITaskExecutionRunnable taskExecutionRunnable) {
        return getSuccessors(taskExecutionRunnable.getName());
    }

    /**
     * 根据任务名称获取任务执行Runnable
     * <p>
     * 从totalTaskExecuteRunnableMap中查找指定名称的任务执行Runnable。
     * </p>
     *
     * @param taskName 任务名称
     * @return 任务执行Runnable，如果不存在则返回null
     */
    @Override
    public ITaskExecutionRunnable getTaskExecutionRunnableByName(final String taskName) {
        return totalTaskExecuteRunnableMap.get(taskName);
    }

    /**
     * 根据任务实例ID获取任务执行Runnable
     * <p>
     * 遍历所有任务执行Runnable，查找taskInstance的ID匹配的任务。
     * 用于通过任务实例ID查找对应的任务执行Runnable。
     * </p>
     *
     * @param taskInstanceId 任务实例ID
     * @return 任务执行Runnable，如果不存在则返回null
     */
    @Override
    public ITaskExecutionRunnable getTaskExecutionRunnableById(final Integer taskInstanceId) {
        return totalTaskExecuteRunnableMap.values()
                .stream()
                .filter(taskExecutionRunnable -> taskExecutionRunnable.getTaskInstance() != null
                        && taskInstanceId.equals(taskExecutionRunnable.getTaskInstance().getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据任务定义代码获取任务执行Runnable
     * <p>
     * 遍历所有任务执行Runnable，查找taskDefinition的code匹配的任务。
     * 用于通过任务定义代码查找对应的任务执行Runnable。
     * </p>
     *
     * @param taskCode 任务定义代码（TaskDefinition.code）
     * @return 任务执行Runnable，如果不存在则返回null
     */
    @Override
    public ITaskExecutionRunnable getTaskExecutionRunnableByTaskCode(final Long taskCode) {
        return totalTaskExecuteRunnableMap.values()
                .stream()
                .filter(taskExecutionRunnable -> taskExecutionRunnable.getTaskDefinition() != null
                        && taskCode.equals(taskExecutionRunnable.getTaskDefinition().getCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断任务是否处于活跃状态（正在执行）
     * <p>
     * 检查任务是否在activeTaskExecutionRunnable集合中。
     * 活跃状态表示任务当前正在执行中。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务处于活跃状态返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableActive(final ITaskExecutionRunnable taskExecutionRunnable) {
        return activeTaskExecutionRunnable.contains(taskExecutionRunnable.getName());
    }

    /**
     * 判断任务是否处于非活跃状态（已执行完成）
     * <p>
     * 检查任务是否在inActiveTaskExecutionRunnable集合中。
     * 非活跃状态表示任务已经执行完成。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务处于非活跃状态返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableInActive(ITaskExecutionRunnable taskExecutionRunnable) {
        return inActiveTaskExecutionRunnable.contains(taskExecutionRunnable.getName());
    }

    /**
     * 判断任务是否被杀死
     * <p>
     * 检查任务是否在killedTaskChains集合中。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务被杀死返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableKilled(final ITaskExecutionRunnable taskExecutionRunnable) {
        return killedTaskChains.contains(taskExecutionRunnable.getName());
    }

    /**
     * 判断任务是否失败
     * <p>
     * 检查任务是否在failureTaskChains集合中。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务失败返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableFailed(ITaskExecutionRunnable taskExecutionRunnable) {
        return failureTaskChains.contains(taskExecutionRunnable.getName());
    }

    /**
     * 判断任务是否暂停
     * <p>
     * 检查任务是否在pausedTaskChains集合中。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务暂停返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnablePaused(ITaskExecutionRunnable taskExecutionRunnable) {
        return pausedTaskChains.contains(taskExecutionRunnable.getName());
    }

    /**
     * 获取所有活跃的任务执行Runnable列表
     * <p>
     * 返回当前正在执行中的所有任务执行Runnable。
     * </p>
     *
     * @return 活跃任务执行Runnable列表，如果没有活跃任务则返回空列表
     */
    @Override
    public List<ITaskExecutionRunnable> getActiveTaskExecutionRunnable() {
        return activeTaskExecutionRunnable
                .stream()
                .map(this::getTaskExecutionRunnableByName)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有任务执行Runnable列表
     * <p>
     * 返回图中的所有任务执行Runnable，无论其状态如何。
     * </p>
     *
     * @return 所有任务执行Runnable列表
     */
    @Override
    public List<ITaskExecutionRunnable> getAllTaskExecutionRunnable() {
        return new ArrayList<>(totalTaskExecuteRunnableMap.values());
    }

    /**
     * 判断任务是否满足触发条件
     * <p>
     * 任务满足触发条件需要满足以下所有条件：
     * <ul>
     *   <li>任务不处于活跃状态（未在执行中）</li>
     *   <li>任务不处于非活跃状态（未执行完成）</li>
     *   <li>所有前置任务都处于非活跃状态（已执行完成）</li>
     *   <li>所有前置任务都没有失败、暂停或被杀死</li>
     * </ul>
     * </p>
     * <p>
     * 此方法用于判断任务是否可以开始执行。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果满足触发条件返回true，否则返回false
     */
    @Override
    public boolean isTriggerConditionMet(final ITaskExecutionRunnable taskExecutionRunnable) {
        if (isTaskExecutionRunnableActive(taskExecutionRunnable)
                || isTaskExecutionRunnableInActive(taskExecutionRunnable)) {
            return false;
        }
        return getPredecessors(taskExecutionRunnable.getName())
                .stream()
                .allMatch(predecessor -> isTaskExecutionRunnableInActive(predecessor)
                        && !isTaskExecutionRunnableFailed(predecessor)
                        && !isTaskExecutionRunnablePaused(predecessor)
                        && !isTaskExecutionRunnableKilled(predecessor));
    }

    /**
     * 判断所有任务执行链是否完成
     * <p>
     * 检查是否还有活跃的任务正在执行。如果没有活跃任务，则表示所有任务都已完成执行。
     * </p>
     *
     * @return 如果所有任务执行链都完成返回true，否则返回false
     */
    @Override
    public boolean isAllTaskExecutionRunnableChainFinish() {
        return activeTaskExecutionRunnable.isEmpty();
    }

    /**
     * 判断所有任务执行链是否成功
     * <p>
     * 所有任务执行链成功的条件：
     * <ul>
     *   <li>所有任务执行链都完成（isAllTaskExecutionRunnableChainFinish返回true）</li>
     *   <li>不存在失败的任务执行链</li>
     *   <li>不存在暂停的任务执行链</li>
     *   <li>不存在被杀死的任务执行链</li>
     * </ul>
     * </p>
     *
     * @return 如果所有任务执行链都成功返回true，否则返回false
     */
    @Override
    public boolean isAllTaskExecutionRunnableChainSuccess() {
        if (!isAllTaskExecutionRunnableChainFinish()) {
            return false;
        }
        return !isExistFailureTaskExecutionRunnableChain()
                && !isExistPauseTaskExecutionRunnableChain()
                && !isExistKillTaskExecutionRunnableChain();
    }

    /**
     * 判断是否存在失败的任务执行链
     *
     * @return 如果存在失败的任务执行链返回true，否则返回false
     */
    @Override
    public boolean isExistFailureTaskExecutionRunnableChain() {
        return CollectionUtils.isNotEmpty(failureTaskChains);
    }

    /**
     * 判断是否存在暂停的任务执行链
     *
     * @return 如果存在暂停的任务执行链返回true，否则返回false
     */
    @Override
    public boolean isExistPauseTaskExecutionRunnableChain() {
        return CollectionUtils.isNotEmpty(pausedTaskChains);
    }

    /**
     * 判断是否存在被杀死的任务执行链
     *
     * @return 如果存在被杀死的任务执行链返回true，否则返回false
     */
    @Override
    public boolean isExistKillTaskExecutionRunnableChain() {
        return CollectionUtils.isNotEmpty(killedTaskChains);
    }

    /**
     * 标记任务为活跃状态（开始执行）
     * <p>
     * 将任务添加到activeTaskExecutionRunnable集合，表示任务开始执行。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     */
    @Override
    public void markTaskExecutionRunnableActive(final ITaskExecutionRunnable taskExecutionRunnable) {
        activeTaskExecutionRunnable.add(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务为非活跃状态（执行完成）
     * <p>
     * 将任务从activeTaskExecutionRunnable集合移除，并添加到inActiveTaskExecutionRunnable集合，
     * 表示任务执行完成。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     */
    @Override
    public void markTaskExecutionRunnableInActive(final ITaskExecutionRunnable taskExecutionRunnable) {
        activeTaskExecutionRunnable.remove(taskExecutionRunnable.getName());
        inActiveTaskExecutionRunnable.add(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务执行链为失败状态
     * <p>
     * 将任务添加到failureTaskChains集合，并验证任务的TaskInstance状态是否为FAILURE。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable，其TaskInstance状态必须为FAILURE
     * @throws IllegalStateException 如果任务的TaskInstance状态不是FAILURE
     */
    @Override
    public void markTaskExecutionRunnableChainFailure(final ITaskExecutionRunnable taskExecutionRunnable) {
        assertTaskExecutionRunnableState(taskExecutionRunnable, TaskExecutionStatus.FAILURE);
        failureTaskChains.add(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务执行链为暂停状态
     * <p>
     * 将任务添加到pausedTaskChains集合，并验证任务的TaskInstance状态是否为PAUSE。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable，其TaskInstance状态必须为PAUSE
     * @throws IllegalStateException 如果任务的TaskInstance状态不是PAUSE
     */
    @Override
    public void markTaskExecutionRunnableChainPause(final ITaskExecutionRunnable taskExecutionRunnable) {
        assertTaskExecutionRunnableState(taskExecutionRunnable, TaskExecutionStatus.PAUSE);
        pausedTaskChains.add(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务执行链为杀死状态
     * <p>
     * 将任务添加到killedTaskChains集合，并验证任务的TaskInstance状态是否为KILL。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable，其TaskInstance状态必须为KILL
     * @throws IllegalStateException 如果任务的TaskInstance状态不是KILL
     */
    @Override
    public void markTaskExecutionRunnableChainKill(final ITaskExecutionRunnable taskExecutionRunnable) {
        assertTaskExecutionRunnableState(taskExecutionRunnable, TaskExecutionStatus.KILL);
        killedTaskChains.add(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务为跳过状态
     * <p>
     * 重载方法，通过任务执行Runnable标记任务为跳过。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     */
    @Override
    public void markTaskSkipped(final ITaskExecutionRunnable taskExecutionRunnable) {
        markTaskSkipped(taskExecutionRunnable.getName());
    }

    /**
     * 标记任务为跳过状态
     * <p>
     * 将任务添加到skippedTask集合，表示该任务将被跳过，不会被执行。
     * </p>
     *
     * @param taskName 任务名称
     */
    @Override
    public void markTaskSkipped(final String taskName) {
        skippedTask.add(taskName);
    }

    /**
     * 判断任务是否是任务链的结束
     * <p>
     * 任务链结束的条件（满足任一条件）：
     * <ul>
     *   <li>任务没有后继任务（successors为空）</li>
     *   <li>任务被杀死</li>
     *   <li>任务被暂停</li>
     *   <li>任务失败</li>
     * </ul>
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务链结束返回true，否则返回false
     */
    @Override
    public boolean isEndOfTaskChain(final ITaskExecutionRunnable taskExecutionRunnable) {
        return successors.get(taskExecutionRunnable.getName()).isEmpty()
                || isTaskExecutionRunnableKilled(taskExecutionRunnable)
                || isTaskExecutionRunnablePaused(taskExecutionRunnable)
                || isTaskExecutionRunnableFailed(taskExecutionRunnable);
    }

    /**
     * 判断任务是否被跳过
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务被跳过返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableSkipped(final ITaskExecutionRunnable taskExecutionRunnable) {
        return skippedTask.contains(taskExecutionRunnable.getName());
    }

    /**
     * 判断任务是否被禁用（禁止执行）
     * <p>
     * 检查任务的TaskDefinition的Flag是否为NO（禁用）。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务被禁用返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableForbidden(final ITaskExecutionRunnable taskExecutionRunnable) {
        return (taskExecutionRunnable.getTaskDefinition().getFlag() == Flag.NO);
    }

    /**
     * 判断任务是否正在重试
     * <p>
     * 任务正在重试的条件（必须全部满足）：
     * <ul>
     *   <li>任务的TaskInstance已初始化</li>
     *   <li>任务的TaskInstance状态为FAILURE</li>
     *   <li>任务可以重试（isTaskInstanceCanRetry返回true）</li>
     *   <li>任务处于活跃状态（正在执行中）</li>
     * </ul>
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果任务正在重试返回true，否则返回false
     */
    @Override
    public boolean isTaskExecutionRunnableRetrying(final ITaskExecutionRunnable taskExecutionRunnable) {
        if (!taskExecutionRunnable.isTaskInstanceInitialized()) {
            return false;
        }
        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        return taskInstance.getState() == TaskExecutionStatus.FAILURE && taskExecutionRunnable.isTaskInstanceCanRetry()
                && isTaskExecutionRunnableActive(taskExecutionRunnable);
    }

    /**
     * Whether all predecessors are skipped.
     * <p> Only when all predecessors are skipped, will return true. If the given task doesn't have any predecessors, will return false.
     */
    @Override
    public boolean isAllPredecessorsSkipped(final ITaskExecutionRunnable taskExecutionRunnable) {
        final List<ITaskExecutionRunnable> predecessors = getPredecessors(taskExecutionRunnable.getName());
        if (CollectionUtils.isEmpty(predecessors)) {
            return false;
        }
        return CollectionUtils.isEmpty(predecessors)
                || predecessors.stream().allMatch(this::isTaskExecutionRunnableSkipped);
    }

    /**
     * 判断任务的所有后继任务是否都是条件任务或被跳过
     * <p>
     * 检查任务的所有后继任务，如果所有后继任务都满足以下条件之一，则返回true：
     * <ul>
     *   <li>后继任务被跳过</li>
     *   <li>后继任务是条件任务（ConditionTask）</li>
     * </ul>
     * </p>
     * <p>
     * 如果任务没有后继任务，返回false。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @return 如果所有后继任务都是条件任务或被跳过返回true，否则返回false
     */
    @Override
    public boolean isAllSuccessorsAreConditionTask(final ITaskExecutionRunnable taskExecutionRunnable) {
        final List<ITaskExecutionRunnable> successors = getSuccessors(taskExecutionRunnable.getName());
        if (CollectionUtils.isEmpty(successors)) {
            return false;
        }
        return successors.stream().allMatch(
                successor -> isTaskExecutionRunnableSkipped(successor)
                        || TaskTypeUtils.isConditionTask(taskExecutionRunnable.getTaskInstance().getTaskType()));
    }

    /**
     * 断言任务执行Runnable的状态
     * <p>
     * 验证任务的TaskInstance状态是否为指定的状态，如果不是则抛出IllegalStateException。
     * 用于状态标记方法中的状态验证。
     * </p>
     *
     * @param taskExecutionRunnable 任务执行Runnable
     * @param taskExecutionStatus 期望的任务执行状态
     * @throws IllegalStateException 如果任务的TaskInstance状态与期望状态不一致
     */
    private void assertTaskExecutionRunnableState(final ITaskExecutionRunnable taskExecutionRunnable,
                                                  final TaskExecutionStatus taskExecutionStatus) {
        final TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
        if (taskInstance.getState() == taskExecutionStatus) {
            return;
        }
        throw new IllegalStateException(
                "The task: " + taskExecutionRunnable.getName() + " state: " + taskInstance.getState() + " is not "
                        + taskExecutionStatus);
    }

}
