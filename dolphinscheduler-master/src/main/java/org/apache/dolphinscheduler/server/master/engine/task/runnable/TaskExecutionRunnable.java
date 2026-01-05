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

package org.apache.dolphinscheduler.server.master.engine.task.runnable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import org.apache.dolphinscheduler.dao.entity.Project;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBus;
import org.apache.dolphinscheduler.server.master.engine.graph.IWorkflowExecutionGraph;
import org.apache.dolphinscheduler.server.master.engine.task.client.ITaskExecutorClient;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskKillLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskPauseLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.runner.TaskExecutionContextFactory;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationContext;

/**
 * 任务执行Runnable实现类
 * <p>
 * TaskExecutionRunnable是ITaskExecutionRunnable接口的核心实现类，负责管理单个任务实例的完整生命周期。
 * 它封装了任务实例的创建、初始化、重试、故障转移、暂停、终止等所有操作。
 * </p>
 * <p>
 * 核心特性：
 * <ul>
 *   <li>延迟创建：任务实例（TaskInstance）可以在首次触发时才创建，避免不必要的数据库写入</li>
 *   <li>生命周期管理：提供完整的任务生命周期管理，包括初始化、重试、故障转移等</li>
 *   <li>上下文管理：负责创建和管理TaskExecutionContext，包含任务执行所需的所有信息</li>
 *   <li>事件驱动：通过WorkflowEventBus发布事件，实现解耦的任务执行流程</li>
 *   <li>优先级排序：实现Comparable接口，支持按优先级排序（工作流优先级、任务优先级、任务组优先级、提交时间）</li>
 * </ul>
 * </p>
 * <p>
 * 任务实例的创建时机：
 * <ol>
 *   <li>首次运行：taskInstance为null，通过initializeFirstRunTaskInstance()创建</li>
 *   <li>故障恢复：从已有TaskInstance恢复，通过TaskExecutionRunnableBuilder传入</li>
 *   <li>重试：通过retry()创建新的重试任务实例</li>
 *   <li>故障转移：通过failover()创建新的故障转移任务实例</li>
 * </ol>
 * </p>
 */
@Slf4j
public class TaskExecutionRunnable implements ITaskExecutionRunnable {

    /**
     * Spring应用上下文，用于获取各种Bean（如TaskInstanceFactories、TaskExecutionContextFactory等）
     */
    private final ApplicationContext applicationContext;

    /**
     * 工作流执行图，包含所有任务执行Runnable的依赖关系和拓扑结构
     * 用于任务调度和依赖管理
     */
    @Getter
    private final IWorkflowExecutionGraph workflowExecutionGraph;

    /**
     * 工作流事件总线，用于发布和订阅工作流相关的事件
     * 实现任务执行流程的事件驱动架构
     */
    @Getter
    private final WorkflowEventBus workflowEventBus;

    /**
     * 工作流定义，包含工作流的元数据信息（如名称、版本、参数等）
     */
    @Getter
    private final WorkflowDefinition workflowDefinition;

    /**
     * 项目信息，包含项目的基本信息和配置
     */
    @Getter
    private final Project project;

    /**
     * 工作流实例，表示一个正在运行的工作流实例
     * 包含工作流实例的状态、参数、执行时间等信息
     */
    @Getter
    private final WorkflowInstance workflowInstance;

    /**
     * 任务实例，可能为null（如果任务尚未初始化）
     * <p>
     * 对于新启动的工作流，taskInstance初始为null，在首次触发时通过initializeFirstRunTaskInstance()创建。
     * 对于故障恢复的场景，taskInstance在创建TaskExecutionRunnable时就已经存在。
     * </p>
     * <p>
     * 注意：taskInstance可能会在重试或故障转移时被替换为新的实例。
     * </p>
     */
    @Getter
    private @Nullable TaskInstance taskInstance;

    /**
     * 任务定义，包含任务的元数据信息（如任务类型、参数、超时策略等）
     */
    @Getter
    private final TaskDefinition taskDefinition;

    /**
     * 任务执行上下文，包含任务执行所需的所有信息
     * <p>
     * TaskExecutionContext会被序列化并发送给Worker节点执行。
     * 只有在taskInstance已初始化后，taskExecutionContext才会被创建。
     * </p>
     * <p>
     * 注意：当taskInstance发生变化时（重试、故障转移），需要重新初始化taskExecutionContext。
     * </p>
     */
    @Getter
    private TaskExecutionContext taskExecutionContext;

    /**
     * 构造函数
     * <p>
     * 从TaskExecutionRunnableBuilder中获取所有必要的组件和实体。
     * 如果传入的taskInstance不为null（即任务实例已初始化），则立即初始化TaskExecutionContext。
     * </p>
     *
     * @param taskExecutionRunnableBuilder 任务执行Runnable建造者，包含构建TaskExecutionRunnable所需的所有信息
     */
    public TaskExecutionRunnable(TaskExecutionRunnableBuilder taskExecutionRunnableBuilder) {
        // 从建造者中获取Spring应用上下文
        this.applicationContext = taskExecutionRunnableBuilder.getApplicationContext();
        // 验证并设置工作流执行图（不能为null）
        this.workflowExecutionGraph = checkNotNull(taskExecutionRunnableBuilder.getWorkflowExecutionGraph());
        // 验证并设置工作流事件总线（不能为null）
        this.workflowEventBus = checkNotNull(taskExecutionRunnableBuilder.getWorkflowEventBus());
        // 验证并设置工作流定义（不能为null）
        this.workflowDefinition = checkNotNull(taskExecutionRunnableBuilder.getWorkflowDefinition());
        // 验证并设置项目信息（不能为null）
        this.project = checkNotNull(taskExecutionRunnableBuilder.getProject());
        // 验证并设置工作流实例（不能为null）
        this.workflowInstance = checkNotNull(taskExecutionRunnableBuilder.getWorkflowInstance());
        // 验证并设置任务定义（不能为null）
        this.taskDefinition = checkNotNull(taskExecutionRunnableBuilder.getTaskDefinition());
        // 设置任务实例（可能为null）
        this.taskInstance = taskExecutionRunnableBuilder.getTaskInstance();
        // 如果任务实例已初始化，立即初始化任务执行上下文
        if (isTaskInstanceInitialized()) {
            initializeTaskExecutionContext();
        }
    }

    /**
     * 判断任务实例是否已初始化
     * <p>通过检查taskInstance是否为null来判断任务实例是否已初始化</p>
     *
     * @return true表示任务实例已初始化，false表示未初始化
     */
    @Override
    public boolean isTaskInstanceInitialized() {
        return taskInstance != null;
    }

    /**
     * 初始化首次运行的任务实例
     * <p>
     * 如果taskInstance为null，会在任务首次启动时通过TaskStartLifecycleEventHandler调用此方法来创建。
     * 创建的任务实例状态为SUBMITTED_SUCCESS（已提交成功），等待被调度执行。
     * </p>
     * <p>
     * 调用此方法后会：
     * <ol>
     *   <li>创建新的TaskInstance并保存到数据库</li>
     *   <li>初始化TaskExecutionContext</li>
     * </ol>
     * </p>
     * <p>
     * 前提条件：taskInstance必须为null（即任务实例未初始化）
     * </p>
     */
    @Override
    public void initializeFirstRunTaskInstance() {
        // 验证任务实例未初始化，如果已初始化则抛出异常
        checkState(!isTaskInstanceInitialized(),
                "The task instance is already initialized, can't initialize first run task.");
        // 使用FirstRunTaskInstanceFactory创建新的任务实例
        // 该工厂会根据TaskDefinition和WorkflowInstance创建TaskInstance并保存到数据库
        this.taskInstance = applicationContext.getBean(TaskInstanceFactories.class)
                .firstRunTaskInstanceFactory()
                .builder()
                .withTaskDefinition(taskDefinition)
                .withWorkflowInstance(workflowInstance)
                .build();
        // 任务实例创建后，初始化任务执行上下文
        initializeTaskExecutionContext();
    }

    /**
     * 判断任务实例是否可以重试
     * <p>
     * 检查任务的当前重试次数是否小于最大重试次数。
     * 只有当重试次数未达到上限时，任务才可以重试。
     * </p>
     *
     * @return true表示可以重试，false表示已达到最大重试次数
     */
    @Override
    public boolean isTaskInstanceCanRetry() {
        return taskInstance.getRetryTimes() < taskInstance.getMaxRetryTimes();
    }

    /**
     * 重试任务执行
     * <p>
     * 会创建新的重试任务实例并启动执行。
     * 重试时会增加重试次数，并重新初始化TaskExecutionContext。
     * </p>
     * <p>
     * 流程：
     * <ol>
     *   <li>使用RetryTaskInstanceFactory创建新的重试任务实例（重试次数+1）</li>
     *   <li>重新初始化TaskExecutionContext（因为taskInstance已变化）</li>
     *   <li>发布TaskStartLifecycleEvent事件，触发任务重新执行</li>
     * </ol>
     * </p>
     * <p>
     * 前提条件：任务实例必须已初始化（isTaskInstanceInitialized()返回true）
     * </p>
     */
    @Override
    public void retry() {
        // 验证任务实例已初始化，如果未初始化则抛出异常
        checkState(isTaskInstanceInitialized(), "The task instance is not initialized, can't initialize retry task.");
        // 使用RetryTaskInstanceFactory创建新的重试任务实例
        // 该工厂会基于现有taskInstance创建新的重试任务实例，重试次数+1，状态重置为SUBMITTED_SUCCESS
        this.taskInstance = applicationContext.getBean(TaskInstanceFactories.class)
                .retryTaskInstanceFactory()
                .builder()
                .withTaskInstance(taskInstance)
                .build();
        // 重新初始化任务执行上下文（因为taskInstance已变化）
        initializeTaskExecutionContext();
        // 发布任务启动生命周期事件，触发任务重新执行
        getWorkflowEventBus().publish(TaskStartLifecycleEvent.of(this));
    }

    /**
     * 故障转移任务执行
     * <p>
     * 当Master发生故障或Worker不可达时，需要将任务重新分配给其他Worker执行。
     * 故障转移会先尝试从执行器接管任务，如果接管失败，则创建新的故障转移任务实例。
     * </p>
     * <p>
     * 流程：
     * <ol>
     *   <li>首先尝试从执行器接管任务（takeOverTaskFromExecutor）</li>
     *   <li>如果接管成功，直接返回，无需重建任务实例</li>
     *   <li>如果接管失败，使用FailoverTaskInstanceFactory创建新的故障转移任务实例</li>
     *   <li>重新初始化TaskExecutionContext</li>
     *   <li>发布TaskStartLifecycleEvent事件，触发任务重新执行</li>
     * </ol>
     * </p>
     * <p>
     * 前提条件：任务实例必须已初始化（isTaskInstanceInitialized()返回true）
     * </p>
     */
    @Override
    public void failover() {
        // 验证任务实例已初始化，如果未初始化则抛出异常
        checkState(isTaskInstanceInitialized(), "The task instance is not initialized, can't failover.");
        // 尝试从执行器接管任务（重新分配工作流实例主机）
        // 如果接管成功，说明任务已被其他Worker接管，无需重建任务实例
        if (takeOverTaskFromExecutor()) {
            log.info("Failover task success, the task {} has been taken-over from executor", taskInstance.getName());
            return;
        }
        // 接管失败，使用FailoverTaskInstanceFactory创建新的故障转移任务实例
        // 该工厂会基于现有taskInstance创建新的故障转移任务实例，状态重置为SUBMITTED_SUCCESS
        this.taskInstance = applicationContext.getBean(TaskInstanceFactories.class)
                .failoverTaskInstanceFactory()
                .builder()
                .withTaskInstance(taskInstance)
                .build();
        // 重新初始化任务执行上下文（因为taskInstance已变化）
        initializeTaskExecutionContext();

        // 发布任务启动生命周期事件，触发任务重新执行
        getWorkflowEventBus().publish(TaskStartLifecycleEvent.of(this));
    }

    /**
     * 暂停任务执行
     * <p>
     * 通过WorkflowEventBus发布TaskPauseLifecycleEvent事件，
     * 由TaskPauseLifecycleEventHandler来处理任务暂停逻辑。
     * </p>
     * <p>
     * 暂停逻辑通常包括：
     * <ul>
     *   <li>向Worker发送暂停指令</li>
     *   <li>更新TaskInstance状态为PAUSE</li>
     * </ul>
     * </p>
     */
    @Override
    public void pause() {
        // 发布任务暂停生命周期事件
        getWorkflowEventBus().publish(TaskPauseLifecycleEvent.of(this));
    }

    /**
     * 终止任务执行
     * <p>
     * 通过WorkflowEventBus发布TaskKillLifecycleEvent事件，
     * 由TaskKillLifecycleEventHandler来处理任务终止逻辑。
     * </p>
     * <p>
     * 终止逻辑通常包括：
     * <ul>
     *   <li>向Worker发送终止指令</li>
     *   <li>更新TaskInstance状态为KILL</li>
     *   <li>清理相关资源</li>
     * </ul>
     * </p>
     */
    @Override
    public void kill() {
        // 发布任务终止生命周期事件
        getWorkflowEventBus().publish(TaskKillLifecycleEvent.of(this));
    }

    /**
     * 初始化任务执行上下文
     * <p>
     * 创建TaskExecutionContext对象，包含任务执行所需的所有信息。
     * TaskExecutionContext会被序列化并发送给Worker节点执行。
     * </p>
     * <p>
     * TaskExecutionContext包含的信息：
     * <ul>
     *   <li>任务实例相关信息（ID、名称、时间等）</li>
     *   <li>任务定义相关信息（参数、超时策略等）</li>
     *   <li>工作流实例相关信息（ID、调度时间、全局参数等）</li>
     *   <li>资源参数（数据源、文件等）</li>
     *   <li>业务参数（合并后的参数）</li>
     *   <li>环境配置</li>
     *   <li>K8s相关配置（如果是K8s任务）</li>
     * </ul>
     * </p>
     * <p>
     * 前提条件：taskInstance必须已初始化（不为null）
     * </p>
     */
    private void initializeTaskExecutionContext() {
        // 验证任务实例已初始化，如果未初始化则抛出异常
        checkState(isTaskInstanceInitialized(), "The task instance is null, can't initialize TaskExecutionContext.");
        // 构建TaskExecutionContextCreateRequest请求对象
        final TaskExecutionContextCreateRequest request = TaskExecutionContextCreateRequest.builder()
                .workflowDefinition(workflowDefinition)
                .project(project)
                .workflowInstance(workflowInstance)
                .taskDefinition(taskDefinition)
                .taskInstance(taskInstance)
                .build();
        // 使用TaskExecutionContextFactory创建TaskExecutionContext
        // TaskExecutionContextFactory会根据request中的信息，从各个实体中提取所需字段并合并参数
        this.taskExecutionContext = applicationContext.getBean(TaskExecutionContextFactory.class)
                .createTaskExecutionContext(request);
    }

    /**
     * 从执行器接管任务
     * <p>
     * 尝试重新分配工作流实例的主机地址，从而从其他执行器接管任务。
     * 这个方法在故障转移时会被调用，如果任务已经被其他Worker接管，则无需重建任务实例。
     * </p>
     * <p>
     * 接管成功的条件：
     * <ul>
     *   <li>Worker节点可达</li>
     *   <li>任务仍在运行中</li>
     *   <li>工作流实例主机地址能够成功重新分配</li>
     * </ul>
     * </p>
     *
     * @return true表示接管成功，false表示接管失败
     */
    private boolean takeOverTaskFromExecutor() {
        // 验证任务实例已初始化，如果未初始化则抛出异常
        checkState(isTaskInstanceInitialized(), "The task instance is null, can't take over from executor.");
        try {
            // 通过ITaskExecutorClient重新分配工作流实例主机地址
            // 如果成功，说明任务已被其他Worker接管，返回true
            return applicationContext.getBean(ITaskExecutorClient.class).reassignWorkflowInstanceHost(this);
        } catch (Exception ex) {
            // 接管失败，记录警告日志并返回false
            log.warn("Take over task: {} failed", taskInstance.getName(), ex);
            return false;
        }
    }

    /**
     * 比较两个TaskExecutionRunnable的优先级
     * <p>
     * 实现Comparable接口，用于任务调度的优先级排序。
     * 优先级比较规则（优先级从高到低）：
     * <ol>
     *   <li>工作流实例优先级（数字越小，优先级越高）</li>
     *   <li>任务实例优先级（数字越小，优先级越高）</li>
     *   <li>任务组优先级（数字越大，优先级越高）</li>
     *   <li>首次提交时间（提交时间越早，优先级越高）</li>
     * </ol>
     * </p>
     * <p>
     * 返回值说明：
     * <ul>
     *   <li>负数：当前对象优先级高于other对象</li>
     *   <li>0：当前对象优先级等于other对象</li>
     *   <li>正数：当前对象优先级低于other对象</li>
     * </ul>
     * </p>
     *
     * @param other 另一个TaskExecutionRunnable对象
     * @return 比较结果
     */
    @Override
    public int compareTo(ITaskExecutionRunnable other) {
        // 如果other为null，当前对象优先级更高
        if (other == null) {
            return 1;
        }
        // 首先比较工作流实例优先级（数字越小，优先级越高）
        int workflowInstancePriorityCompareResult = workflowInstance.getWorkflowInstancePriority().getCode() -
                other.getWorkflowInstance().getWorkflowInstancePriority().getCode();
        if (workflowInstancePriorityCompareResult != 0) {
            return workflowInstancePriorityCompareResult;
        }

        // 其次比较任务实例优先级（数字越小，优先级越高）
        int taskInstancePriorityCompareResult = taskInstance.getTaskInstancePriority().getCode()
                - other.getTaskInstance().getTaskInstancePriority().getCode();
        if (taskInstancePriorityCompareResult != 0) {
            return taskInstancePriorityCompareResult;
        }

        // 然后比较任务组优先级（数字越大，优先级越高，所以需要取负）
        int taskGroupPriorityCompareResult =
                taskInstance.getTaskGroupPriority() - other.getTaskInstance().getTaskGroupPriority();
        if (taskGroupPriorityCompareResult != 0) {
            return -taskGroupPriorityCompareResult;
        }
        // 最后比较首次提交时间（提交时间越早，优先级越高）
        return taskInstance.getFirstSubmitTime().compareTo(other.getTaskInstance().getFirstSubmitTime());
    }

    /**
     * 转换为字符串表示
     * <p>
     * 用于日志输出和调试，包含任务名称和状态信息。
     * </p>
     *
     * @return 字符串表示
     */
    @Override
    public String toString() {
        // 如果任务实例已初始化，包含状态信息
        if (taskInstance != null) {
            return "TaskExecutionRunnable{" + "name=" + getName() + ", state=" + taskInstance.getState() + '}';
        }
        // 如果任务实例未初始化，只包含名称
        return "TaskExecutionRunnable{" + "name=" + getName() + '}';
    }
}
