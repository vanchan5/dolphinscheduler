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

package org.apache.dolphinscheduler.server.master.engine;

import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.plugin.task.api.enums.TaskExecutionStatus;
import org.apache.dolphinscheduler.server.master.engine.command.CommandEngine;
import org.apache.dolphinscheduler.server.master.engine.executor.LogicTaskEngineDelegator;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.AbstractTaskLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskDispatchLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler.AbstractTaskLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler.TaskStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.task.statemachine.ITaskStateAction;
import org.apache.dolphinscheduler.server.master.engine.task.statemachine.TaskSubmittedStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.AbstractWorkflowLifecycleLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.event.WorkflowStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler.AbstractWorkflowLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler.WorkflowStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.IWorkflowStateAction;
import org.apache.dolphinscheduler.server.master.runner.GlobalTaskDispatchWaitingQueueLooper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowEngine implements AutoCloseable {

    @Autowired
    private WorkflowEventBusCoordinator workflowEventBusCoordinator;

    @Autowired
    private CommandEngine commandEngine;

    @Autowired
    private GlobalTaskDispatchWaitingQueueLooper globalTaskDispatchWaitingQueueLooper;

    @Autowired
    private LogicTaskEngineDelegator logicTaskEngineDelegator;

    public void start() {

        /**
         * ============================================================================================
         * 步骤 1: 启动工作流事件总线协调器 (WorkflowEventBusCoordinator)
         * ============================================================================================
         *
         * 【功能说明】
         * WorkflowEventBusCoordinator 是工作流事件总线的协调器，负责管理和分发工作流生命周期事件。
         * 它启动多个 WorkflowEventBusFireWorker 线程，每个线程定期轮询并处理已注册工作流的事件。
         *
         * 【启动流程】
         * 1. 启动 WorkflowEventBusFireWorkers
         *    - 创建 ScheduledExecutorService 线程池（线程数 = CPU核心数 * 2 + 1）
         *    - 为每个线程创建 WorkflowEventBusFireWorker 实例
         *    - 注册所有事件处理器（ILifecycleEventHandler）
         *    - 安排定时任务，每 100ms 触发一次事件处理
         *
         * 【工作原理】
         * - 工作流注册：通过 workflowInstanceId % workerSize 计算槽位，将工作流分配到不同的 Worker
         * - 事件处理：Worker 定期调用 fireAllRegisteredEvent()，从每个工作流的事件总线中取出事件并触发处理器
         * - 事件类型：工作流启动、任务完成、工作流完成、失败等生命周期事件
         *
         * 【为什么最先启动】
         * 后续的 CommandEngine 在创建 WorkflowExecutionRunnable 后，需要注册到事件总线协调器。
         * 因此必须先启动事件总线协调器，确保工作流可以正常注册和接收事件。
         *
         * 【ASCII 流程图】
         *
         *     WorkflowEventBusCoordinator.start()
         *              |
         *              v
         *     WorkflowEventBusFireWorkers.start()
         *              |
         *              +---> 创建线程池 (ScheduledExecutorService)
         *              |         |
         *              |         +---> 线程数 = CPU核心数 * 2 + 1
         *              |         +---> 守护线程 (Daemon Thread)
         *              |
         *              +---> 创建 Worker 数组
         *              |         |
         *              |         +---> Worker[0] -> 注册事件处理器 -> 安排定时任务 (100ms)
         *              |         +---> Worker[1] -> 注册事件处理器 -> 安排定时任务 (100ms)
         *              |         +---> Worker[N] -> 注册事件处理器 -> 安排定时任务 (100ms)
         *              |
         *              v
         *     定时任务循环执行 (每 100ms)
         *              |
         *              +---> Worker[i].fireAllRegisteredEvent()
         *                        |
         *                        +---> 遍历已注册的工作流
         *                        |         |
         *                        |         +---> 从 WorkflowEventBus 取出事件
         *                        |         +---> 根据事件类型匹配处理器
         *                        |         +---> 触发处理器执行业务逻辑
         *
         * 【时序图】
         *
         *     WorkflowEngine    WorkflowEventBusCoordinator    WorkflowEventBusFireWorkers    Worker[0..N]
         *          |                        |                              |                          |
         *          |--start()-------------->|                              |                          |
         *          |                        |--start()--------------------->|                          |
         *          |                        |                              |--创建线程池-------------->|
         *          |                        |                              |--创建Worker数组---------->|
         *          |                        |                              |                          |--注册事件处理器
         *          |                        |                              |--安排定时任务------------>|
         *          |                        |<--started---------------------|                          |
         *          |<--started--------------|                              |                          |
         *          |                        |                              |                          |
         *          |                        |                              |     [定时任务循环]         |
         *          |                        |                              |<--fireAllRegisteredEvent--|
         *          |                        |                              |                          |--处理事件
         *
         * 事件处理流程:
         * {@link AbstractWorkflowLifecycleLifecycleEvent} ->
         * {@link AbstractWorkflowLifecycleEventHandler} ->
         * {@link IWorkflowStateAction} ->
         * {@link AbstractTaskLifecycleEvent} -> {@link TaskStartLifecycleEvent} ->
         * {@link AbstractTaskLifecycleEventHandler} -> {@link TaskStartLifecycleEventHandler} ->
         * 最后触发任务实例开始事件
         *
         * 任务处理逻辑
         * {@link ITaskExecutionRunnable#getTaskInstance() } -> {@link TaskInstance#getState()} ->
         * {@link TaskExecutionStatus} ->
         * {@link ITaskStateAction} ->  {@link }
         *
         *
         */
        workflowEventBusCoordinator.start();

        /**
         * ============================================================================================
         * 步骤 2: 启动命令引擎 (CommandEngine)
         * ============================================================================================
         *
         * 【功能说明】
         * CommandEngine 是 Master 的核心调度线程，负责从数据库消费命令（Command）并触发工作流执行。
         * 它是整个工作流引擎的入口，将数据库中的命令转换为工作流执行任务。
         *
         * 【启动流程】
         * 1. 创建命令处理线程池（线程数 = CPU核心数）
         * 2. 启动守护线程，进入主循环
         * 3. 主循环逻辑：
         *    - 检查服务器负载，如果过载则等待
         *    - 从数据库获取待处理的命令列表（通过 ICommandFetcher）
         *    - 对每个命令异步处理：
         *      a. 创建 WorkflowExecutionRunnable（工作流执行任务）
         *      b. 注册到 WorkflowRepository 和 WorkflowEventBusCoordinator
         *      c. 发布 WorkflowStartLifecycleEvent 启动工作流
         *      d. 更新命令状态（成功/失败）
         *
         * 【命令类型】
         * - START_PROCESS: 启动工作流
         * - START_CURRENT_TASK_PROCESS: 启动当前任务
         * - RECOVER_TOLERANT_FAULT_PROCESS: 容错恢复
         * - RECOVER_SUSPENDED_PROCESS: 恢复暂停的工作流
         * - START_FAILURE_TASK_PROCESS: 启动失败任务
         * - COMPLEMENT_DATA: 补数
         * - SCHEDULER: 定时调度
         * - REPEAT_RUNNING: 重复运行
         * - PAUSE: 暂停
         * - STOP: 停止
         * - RECOVER_WAITING_THREAD: 恢复等待线程
         *
         * 【为什么在事件总线之后启动】
         * CommandEngine 在创建 WorkflowExecutionRunnable 后，需要注册到 WorkflowEventBusCoordinator。
         * {@link WorkflowEventBusCoordinator#registerWorkflowEventBus(IWorkflowExecutionRunnable)}
         * 因此必须先启动事件总线协调器,创建多个WorkflowEventBusFireWorker数组，根据计算出来的workerSlot
         * {@link WorkflowEventBusCoordinator#calculateWorkflowEventBusFireWorkerSlot(IWorkflowExecutionRunnable)},
         * 获取Slot对应的WorkflowEventBusFireWorker,将IWorkflowExecutionRunnable注册到registeredWorkflowExecuteRunnableMap
         * {@link WorkflowEventBusFireWorker#registerWorkflowEventBus(IWorkflowExecutionRunnable)}
         * 确保工作流可以正常注册。
         *
         * 【ASCII 流程图】
         *
         *     CommandEngine.start()
         *              |
         *              +---> 创建命令处理线程池 (ExecutorService)
         *              |         |
         *              |         +---> 线程数 = CPU核心数
         *              |         +---> 守护线程
         *              |
         *              +---> 启动守护线程 (MasterCommandLoopThread)
         *                      |
         *                      v
         *              主循环 (while flag)
         *                      |
         *                      +---> 检查服务器负载
         *                      |         |
         *                      |         +---> 过载? -> 等待 1s -> continue
         *                      |
         *                      +---> 从数据库获取命令列表
         *                      |         |
         *                      |         +---> ICommandFetcher.fetchCommands()
         *                      |         +---> 无命令? -> 等待 1s -> continue
         *                      |
         *                      +---> 异步处理每个命令
         *                              |
         *                              +---> bootstrapCommand()
         *                              |         |
         *                              |         +---> 创建 WorkflowExecutionRunnable
         *                              |
         *                              +---> bootstrapWorkflowExecutionRunnable()
         *                              |         |
         *                              |         +---> 注册到 WorkflowRepository
         *                              |         +---> 注册到 WorkflowEventBusCoordinator
         *                              |         +---> 发布 WorkflowStartLifecycleEvent --> {@link WorkflowStartLifecycleEventHandler#handle(IWorkflowStateAction, IWorkflowExecutionRunnable, WorkflowStartLifecycleEvent)}
         *                              |
         *                              +---> bootstrapSuccess() / bootstrapError()
         *                                      |
         *                                      +---> 更新命令状态
         *
         * 【时序图】
         *
         *     WorkflowEngine    CommandEngine    ICommandFetcher    WorkflowRepository    WorkflowEventBusCoordinator
         *          |                 |                  |                    |                          |
         *          |--start()------->|                  |                    |                          |
         *          |                 |--创建线程池       |                    |                          |
         *          |                 |--启动守护线程     |                    |                          |
         *          |                 |                  |                    |                          |
         *          |                 |     [主循环]      |                    |                          |
         *          |                 |--fetchCommands()>|                    |                          |
         *          |                 |<--commands-------|                    |                          |
         *          |                 |                  |                    |                          |
         *          |                 |--创建Runnable    |                    |                          |
         *          |                 |--put()------------------------------->|                          |
         *          |                 |--registerWorkflowEventBus()---------->|                          |
         *          |                 |--publish(WorkflowStartLifecycleEvent)->|                          |
         */
        commandEngine.start();

        /**
         * ============================================================================================
         * 步骤 3: 启动全局任务分发等待队列循环器 (GlobalTaskDispatchWaitingQueueLooper)
         * ============================================================================================
         *
         * 【功能说明】
         * GlobalTaskDispatchWaitingQueueLooper 是一个守护线程，负责从全局任务分发等待队列中取出任务，
         * 并通过 ITaskExecutorClient 将任务分发到 Worker 节点或 LogicTaskExecutor 执行。
         *
         * 【启动流程】
         * 1. 检查是否已启动（使用 AtomicBoolean 保证线程安全）
         * 2. 启动守护线程，进入主循环
         * 3. 主循环逻辑：
         *    - 从 GlobalTaskDispatchWaitingQueue 中阻塞获取任务（takeTaskExecuteRunnable）
         *    - 检查任务状态（必须是 SUBMITTED_SUCCESS 或 DELAY_EXECUTION）
         *    - 通过 ITaskExecutorClient.dispatch() 分发任务
         *    - 如果分发失败，将任务重新放回队列（带延迟，延迟时间递增，最多 60 秒）
         *
         * 【任务分发机制】
         * - 任务来源：
         * {@link TaskSubmittedStateAction#dispatchEventAction(IWorkflowExecutionRunnable, ITaskExecutionRunnable, TaskDispatchLifecycleEvent)} ->
         * 工作流执行过程中，当任务满足执行条件时，会被放入 GlobalTaskDispatchWaitingQueue
         * - 分发目标：
         *   * PhysicalTaskExecutorClientDelegator: 分发到远程 Worker 节点执行
         *   * LogicTaskExecutorClientDelegator: 分发到本地 LogicTaskExecutor 执行（逻辑任务）
         * - 重试机制：分发失败时，延迟时间 = min(失败次数 * 1秒, 60秒)
         *
         * 【为什么在 CommandEngine 之后启动】
         * CommandEngine 启动工作流后，工作流会创建任务并放入 GlobalTaskDispatchWaitingQueue。
         * 因此需要先启动 CommandEngine，确保有任务可以分发。
         *
         * 【ASCII 流程图】
         *
         *     GlobalTaskDispatchWaitingQueueLooper.start()
         *              |
         *              +---> 检查启动状态 (AtomicBoolean)
         *              |         |
         *              |         +---> 已启动? -> 返回
         *              |
         *              +---> 启动守护线程
         *                      |
         *                      v
         *              主循环 (while RUNNING_FLAG)
         *                      |
         *                      +---> doDispatch()
         *                              |
         *                              +---> 从队列中阻塞获取任务
         *                              |         |
         *                              |         +---> GlobalTaskDispatchWaitingQueue.takeTaskExecuteRunnable()
         *                              |         +---> 使用 PriorityDelayQueue，只取延迟时间 <= 0 的任务
         *                              |
         *                              +---> 检查任务状态
         *                              |         |
         *                              |         +---> SUBMITTED_SUCCESS? -> 继续
         *                              |         +---> DELAY_EXECUTION? -> 继续
         *                              |         +---> 其他状态? -> 跳过
         *                              |
         *                              +---> 分发任务
         *                              |         |
         *                              |         +---> ITaskExecutorClient.dispatch()
         *                              |         |         |
         *                              |         |         +---> PhysicalTaskExecutorClientDelegator
         *                              |         |         |         +---> 分发到远程 Worker
         *                              |         |         |
         *                              |         |         +---> LogicTaskExecutorClientDelegator
         *                              |         |                   +---> 分发到本地 LogicTaskExecutor
         *                              |         |
         *                              |         +---> 成功? -> 继续下一个任务
         *                              |         +---> 失败? -> 重新放入队列（带延迟）
         *                              |                       |
         *                              |                       +---> 延迟时间 = min(失败次数 * 1s, 60s)
         *
         * 【时序图】
         *
         *     WorkflowEngine    GlobalTaskDispatchWaitingQueueLooper    GlobalTaskDispatchWaitingQueue    ITaskExecutorClient
         *          |                            |                                  |                              |
         *          |--start()------------------->|                                  |                              |
         *          |                            |--启动守护线程                     |                              |
         *          |                            |                                  |                              |
         *          |                            |     [主循环]                      |                              |
         *          |                            |--takeTaskExecuteRunnable()------->|                              |
         *          |                            |<--taskExecutionRunnable-----------|                              |
         *          |                            |                                  |                              |
         *          |                            |--dispatch()----------------------------------------------->|
         *          |                            |                                  |                              |--分发到Worker/LogicTaskExecutor
         *          |                            |<--success/failure-------------------------------------------|
         *          |                            |                                  |                              |
         *          |                            |    失败? -> dispatchTaskExecuteRunnableWithDelay()--------->|
         */
        globalTaskDispatchWaitingQueueLooper.start();

        /**
         * ============================================================================================
         * 步骤 4: 启动逻辑任务引擎委托器 (LogicTaskEngineDelegator)
         * ============================================================================================
         *
         * 【功能说明】
         * LogicTaskEngineDelegator 负责管理逻辑任务的执行，包括启动 TaskEngine 和事件报告器。
         * 逻辑任务是在 Master 节点本地执行的任务（如条件分支、子工作流等），不需要分发到 Worker。
         *
         * 【启动流程】
         * 1. 启动 TaskEngine
         *    - TaskEngine 是任务执行引擎，负责管理任务执行线程池
         *    - 提交任务到 TaskEngine 后，会在本地线程池中执行
         * 2. 启动 LogicTaskExecutorLifecycleEventReporter
         *    - 负责接收和报告逻辑任务的生命周期事件（启动、完成、失败等）
         *    - 将事件上报给 Master，用于更新任务状态和工作流状态
         *
         * 【逻辑任务类型】
         * - 条件任务（Condition）：根据条件判断执行分支
         * - 子工作流（SubWorkflow）：执行子工作流
         * - 依赖任务（Dependent）：依赖其他工作流的执行结果
         * - Switch 任务：多分支选择
         *
         * 【为什么最后启动】
         * LogicTaskEngineDelegator 依赖于 GlobalTaskDispatchWaitingQueueLooper。
         * 当任务分发到 LogicTaskExecutorClientDelegator 时，会调用 LogicTaskEngineDelegator.dispatchLogicTask()。
         * 因此需要先启动任务分发循环器，确保逻辑任务可以正常分发和执行。
         *
         * 【ASCII 流程图】
         *
         *     LogicTaskEngineDelegator.start()
         *              |
         *              +---> TaskEngine.start()
         *              |         |
         *              |         +---> 创建任务执行线程池
         *              |         +---> 启动任务调度线程
         *              |
         *              +---> LogicTaskExecutorLifecycleEventReporter.start()
         *                      |
         *                      +---> 启动事件接收线程
         *                      +---> 启动事件上报线程
         *
         *     任务执行流程：
         *
         *     GlobalTaskDispatchWaitingQueueLooper
         *              |
         *              +---> 分发逻辑任务
         *                      |
         *                      v
         *     LogicTaskExecutorClientDelegator.dispatch()
         *              |
         *              +---> LogicTaskEngineDelegator.dispatchLogicTask()
         *                      |
         *                      +---> 创建 ITaskExecutor
         *                      +---> TaskEngine.submitTask()
         *                              |
         *                              +---> 在线程池中执行任务
         *                              +---> 任务完成后，通过 EventReporter 上报事件
         *
         * 【时序图】
         *
         *     WorkflowEngine    LogicTaskEngineDelegator    TaskEngine    LogicTaskExecutorLifecycleEventReporter
         *          |                        |                    |                              |
         *          |--start()-------------->|                    |                              |
         *          |                        |--start()---------->|                              |
         *          |                        |                    |--创建线程池                   |
         *          |                        |                    |--启动调度线程                 |
         *          |                        |--start()----------------------------------------->|
         *          |                        |                    |                              |--启动事件接收线程
         *          |                        |                    |                              |--启动事件上报线程
         *          |                        |<--started----------|                              |
         *          |<--started--------------|                    |                              |
         *          |                        |                    |                              |
         *          |                        |     [任务执行]      |                              |
         *          |                        |<--dispatchLogicTask()                            |
         *          |                        |--submitTask()----->|                              |
         *          |                        |                    |--在线程池执行任务             |
         *          |                        |                    |--任务完成                     |
         *          |                        |                    |--上报事件-------------------->|
         */
        logicTaskEngineDelegator.start();

        /**
         * ============================================================================================
         * 整体启动流程总结
         * ============================================================================================
         *
         * 【启动顺序说明】
         * 1. WorkflowEventBusCoordinator: 必须先启动，为后续工作流注册提供事件总线支持
         * 2. CommandEngine: 启动后开始消费命令并创建工作流，需要事件总线已就绪
         * 3. GlobalTaskDispatchWaitingQueueLooper: 启动后开始分发任务，需要 CommandEngine 已启动
         * 4. LogicTaskEngineDelegator: 最后启动，处理逻辑任务，需要任务分发器已启动
         *
         * 【组件协作关系】
         *
         *     CommandEngine
         *          |
         *          +---> 创建 WorkflowExecutionRunnable
         *          |         |
         *          |         +---> 注册到 WorkflowEventBusCoordinator
         *          |         +---> 发布 WorkflowStartLifecycleEvent
         *          |
         *          v
         *     WorkflowEventBusCoordinator
         *          |
         *          +---> Worker 定期处理事件
         *          |         |
         *          |         +---> 触发任务创建
         *          |         +---> 任务放入 GlobalTaskDispatchWaitingQueue
         *          |
         *          v
         *     GlobalTaskDispatchWaitingQueueLooper
         *          |
         *          +---> 取出任务
         *          |         |
         *          |         +---> 物理任务 -> 分发到 Worker
         *          |         +---> 逻辑任务 -> 分发到 LogicTaskEngineDelegator
         *          |
         *          v
         *     LogicTaskEngineDelegator
         *          |
         *          +---> 执行逻辑任务
         *          +---> 上报任务事件
         *
         * 【完整时序图】
         *
         *     WorkflowEngine
         *          |
         *          |--start()--------------------------------------------------------+
         *          |                                                                 |
         *          |  1. WorkflowEventBusCoordinator.start()                        |
         *          |     |                                                           |
         *          |     +---> 启动 Worker 线程池                                    |
         *          |     +---> 安排定时任务（每 100ms 处理事件）                      |
         *          |                                                                 |
         *          |  2. CommandEngine.start()                                      |
         *          |     |                                                           |
         *          |     +---> 启动守护线程                                          |
         *          |     +---> 开始消费数据库命令                                    |
         *          |     +---> 创建工作流并注册到事件总线                            |
         *          |                                                                 |
         *          |  3. GlobalTaskDispatchWaitingQueueLooper.start()               |
         *          |     |                                                           |
         *          |     +---> 启动守护线程                                          |
         *          |     +---> 开始从队列中取出任务并分发                            |
         *          |                                                                 |
         *          |  4. LogicTaskEngineDelegator.start()                           |
         *          |     |                                                           |
         *          |     +---> 启动 TaskEngine                                        |
         *          |     |     |                                                     |
         *          |     |     +---> 创建任务执行线程池                               |
         *          |     |     +---> 启动任务调度线程                                 |
         *          |     |                                                           |
         *          |     +---> 启动 LogicTaskExecutorLifecycleEventReporter          |
         *          |           |                                                     |
         *          |           +---> 启动事件接收线程                                 |
         *          |           +---> 启动事件上报线程                                 |
         *          |                                                                 |
         *          |<--所有组件启动完成-----------------------------------------------+
         *          |                                                                 |
         *          |  [运行阶段]                                                       |
         *          |                                                                 |
         *          |  CommandEngine 消费命令 -> 创建工作流 -> 注册到事件总线           |
         *          |       |                                                          |
         *          |       v                                                          |
         *          |  WorkflowEventBusCoordinator 处理事件 -> 创建任务                |
         *          |       |                                                          |
         *          |       v                                                          |
         *          |  GlobalTaskDispatchWaitingQueueLooper 分发任务                   |
         *          |       |                                                          |
         *          |       +---> 物理任务 -> Worker 节点执行                         |
         *          |       +---> 逻辑任务 -> LogicTaskEngineDelegator 执行          |
         *          |               |                                                  |
         *          |               +---> TaskEngine 在线程池中执行                     |
         *          |               +---> 事件上报 -> 更新任务状态                     |
         *          |                                                                 |
         *          +------------------------------------------------------------------+
         */

        log.info("WorkflowEngine started");
    }

    @Override
    public void close() throws Exception {
        try (
                final CommandEngine ignore1 = commandEngine;
                final WorkflowEventBusCoordinator ignore2 = workflowEventBusCoordinator;
                final GlobalTaskDispatchWaitingQueueLooper ignore3 = globalTaskDispatchWaitingQueueLooper;
                final LogicTaskEngineDelegator ignore5 = logicTaskEngineDelegator) {
            // closed the resource
        }
    }
}
