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

import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBus;
import org.apache.dolphinscheduler.server.master.engine.graph.IWorkflowExecutionGraph;

/**
 * 任务执行Runnable接口
 * <p>
 * 表示一个正在运行或待运行的任务实例，属于某个WorkflowExecutionRunnable。
 * ITaskExecutionRunnable是任务执行的核心抽象，封装了任务实例的完整生命周期管理。
 * </p>
 * <p>
 * 核心职责：
 * <ol>
 *   <li>管理任务实例的创建和初始化</li>
 *   <li>处理任务的重试、故障转移、暂停、终止等操作</li>
 *   <li>提供任务执行的上下文信息（TaskExecutionContext）</li>
 *   <li>与工作流执行图（IWorkflowExecutionGraph）和事件总线（WorkflowEventBus）交互</li>
 * </ol>
 * </p>
 * <p>
 * 生命周期状态：
 * <ul>
 *   <li>未初始化：taskInstance为null，尚未创建任务实例</li>
 *   <li>已初始化：taskInstance不为null，任务实例已创建</li>
 *   <li>运行中：任务正在执行</li>
 *   <li>可重试：任务失败但可以重试</li>
 *   <li>已完成/失败：任务执行结束</li>
 * </ul>
 * </p>
 * <p>
 * 实现类：
 * <ul>
 *   <li>TaskExecutionRunnable: 主要的实现类，包含完整的任务执行逻辑</li>
 * </ul>
 * </p>
 */
public interface ITaskExecutionRunnable
        extends
            Comparable<ITaskExecutionRunnable> {

    /**
     * 获取任务实例ID
     * <p>
     * 注意：ID可能会变化，因为任务实例可能会被重新生成（如重试、故障转移时）
     * </p>
     *
     * @return 任务实例ID
     */
    default int getId() {
        return getTaskInstance().getId();
    }

    /**
     * 获取任务名称
     * <p>从TaskDefinition中获取任务名称</p>
     *
     * @return 任务名称
     */
    default String getName() {
        return getTaskDefinition().getName();
    }

    /**
     * 判断任务实例是否已初始化
     * <p>
     * 任务实例的初始化时机：
     * <ul>
     *   <li>未初始化：如果ITaskExecutionRunnable从未被触发执行，则taskInstance为null，处于未初始化状态</li>
     *   <li>已初始化：如果ITaskExecutionRunnable是通过故障转移创建的，或者从已有任务恢复的，则taskInstance不为null，处于已初始化状态</li>
     * </ul>
     * </p>
     * <p>
     * 对于新启动的工作流，任务实例会在首次触发时才创建（延迟创建），
     * 这样可以避免不必要的数据写入，只在真正需要执行时才创建任务实例。
     * </p>
     *
     * @return true表示任务实例已初始化，false表示未初始化
     */
    boolean isTaskInstanceInitialized();

    /**
     * 初始化首次运行的任务实例
     * <p>
     * 使用FirstRunTaskInstanceFactory创建新的任务实例。
     * 这个方法会在任务首次被触发执行时调用，创建任务实例并初始化TaskExecutionContext。
     * </p>
     * <p>
     * 创建的任务实例状态为SUBMITTED_SUCCESS（已提交成功），等待被调度执行。
     * </p>
     * <p>
     * 调用时机：
     * <ul>
     *   <li>任务首次启动时，由TaskStartLifecycleEventHandler触发</li>
     *   <li>确保isTaskInstanceInitialized()返回false才能调用</li>
     * </ul>
     * </p>
     */
    void initializeFirstRunTaskInstance();

    /**
     * 判断任务实例是否可以重试
     * <p>
     * 检查任务的当前重试次数是否小于最大重试次数。
     * </p>
     *
     * @return true表示可以重试，false表示已达到最大重试次数
     */
    boolean isTaskInstanceCanRetry();

    /**
     * 重试任务执行
     * <p>
     * 会创建新的重试任务实例并启动执行。
     * 重试时会增加重试次数，并重新初始化TaskExecutionContext。
     * </p>
     * <p>
     * 流程：
     * <ol>
     *   <li>使用RetryTaskInstanceFactory创建新的重试任务实例</li>
     *   <li>重新初始化TaskExecutionContext</li>
     *   <li>发布TaskStartLifecycleEvent事件，触发任务执行</li>
     * </ol>
     * </p>
     * <p>
     * 前提条件：任务实例必须已初始化（isTaskInstanceInitialized()返回true）
     * </p>
     */
    void retry();

    /**
     * 故障转移任务执行
     * <p>
     * 当Master发生故障或Worker不可达时，需要将任务重新分配给其他Worker执行。
     * 故障转移逻辑会根据任务实例的状态来判断是否需要重新创建任务实例。
     * </p>
     * <p>
     * 流程：
     * <ol>
     *   <li>首先尝试从执行器接管任务（takeOverTaskFromExecutor）</li>
     *   <li>如果接管失败，使用FailoverTaskInstanceFactory创建新的故障转移任务实例</li>
     *   <li>重新初始化TaskExecutionContext</li>
     *   <li>发布TaskStartLifecycleEvent事件，触发任务重新执行</li>
     * </ol>
     * </p>
     * <p>
     * 前提条件：任务实例必须已初始化（isTaskInstanceInitialized()返回true）
     * </p>
     */
    void failover();

    /**
     * 暂停任务执行
     * <p>
     * 发布TaskPauseLifecycleEvent事件，由相应的事件处理器来处理任务暂停逻辑。
     * </p>
     */
    void pause();

    /**
     * 终止任务执行
     * <p>
     * 发布TaskKillLifecycleEvent事件，由相应的事件处理器来处理任务终止逻辑。
     * </p>
     */
    void kill();

    /**
     * 获取工作流事件总线
     * <p>用于发布和订阅工作流相关的事件</p>
     *
     * @return 工作流事件总线
     */
    WorkflowEventBus getWorkflowEventBus();

    /**
     * 获取工作流执行图
     * <p>
     * 工作流执行图包含了所有任务执行Runnable的依赖关系，
     * 用于任务调度和依赖管理。
     * </p>
     *
     * @return 工作流执行图
     */
    IWorkflowExecutionGraph getWorkflowExecutionGraph();

    /**
     * 获取工作流实例
     * <p>该任务所属的工作流实例</p>
     *
     * @return 工作流实例
     */
    WorkflowInstance getWorkflowInstance();

    /**
     * 获取任务实例
     * <p>
     * 注意：如果任务尚未初始化，可能返回null。
     * 可以通过isTaskInstanceInitialized()判断任务实例是否已初始化。
     * </p>
     *
     * @return 任务实例，可能为null
     */
    TaskInstance getTaskInstance();

    /**
     * 获取任务定义
     * <p>任务的元数据信息，如任务类型、参数等</p>
     *
     * @return 任务定义
     */
    TaskDefinition getTaskDefinition();

    /**
     * 获取任务执行上下文
     * <p>
     * TaskExecutionContext包含了任务执行所需的所有信息，
     * 包括任务参数、工作流参数、资源信息、环境配置等。
     * 这个上下文会被序列化并发送给Worker节点执行。
     * </p>
     * <p>
     * 注意：只有在任务实例已初始化后，TaskExecutionContext才会被创建。
     * </p>
     *
     * @return 任务执行上下文，可能为null（如果任务实例未初始化）
     */
    TaskExecutionContext getTaskExecutionContext();
}
