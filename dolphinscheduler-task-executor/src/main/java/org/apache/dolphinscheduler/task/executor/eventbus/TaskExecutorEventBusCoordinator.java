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

package org.apache.dolphinscheduler.task.executor.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.task.executor.ITaskExecutor;
import org.apache.dolphinscheduler.task.executor.ITaskExecutorRepository;
import org.apache.dolphinscheduler.task.executor.events.AbstractTaskExecutorLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.ITaskExecutorLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorDispatchedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorFailedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorFinalizeLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorKillLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorKilledLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorPauseLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorPausedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorRuntimeContextChangedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorStartedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorSuccessLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.listener.ITaskExecutorLifecycleEventListener;
import org.apache.dolphinscheduler.task.executor.log.TaskExecutorMDCUtils;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务执行器事件总线协调器
 * 
 * <p>本协调器是任务引擎中负责监听和处理任务执行器生命周期事件的核心组件。
 * 它通过定期轮询机制，从所有任务执行器的事件总线中取出生命周期事件，并根据事件类型
 * 触发相应的监听器处理业务逻辑。
 * 
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>事件轮询</b>: 主线程池每50ms轮询一次，检查所有任务执行器的事件总线是否有待处理事件</li>
 *   <li><b>事件分发</b>: 发现事件后，将事件处理任务提交到工作线程池异步执行</li>
 *   <li><b>生命周期事件处理</b>: 根据事件类型（DISPATCHED、RUNNING、SUCCESS、FAILED等）调用相应的监听器方法</li>
 *   <li><b>监听器管理</b>: 管理所有注册的生命周期事件监听器，支持多个监听器同时处理同一事件</li>
 * </ul>
 * 
 * <h3>支持的生命周期事件类型</h3>
 * <ul>
 *   <li><b>DISPATCHED</b>: 任务已分发到容器</li>
 *   <li><b>RUNNING</b>: 任务开始执行</li>
 *   <li><b>RUNTIME_CONTEXT_CHANGE</b>: 运行时上下文变更（如应用链接更新）</li>
 *   <li><b>PAUSE</b>: 暂停请求</li>
 *   <li><b>PAUSED</b>: 任务已暂停</li>
 *   <li><b>KILL</b>: 杀死请求</li>
 *   <li><b>KILLED</b>: 任务已杀死</li>
 *   <li><b>SUCCESS</b>: 任务执行成功</li>
 *   <li><b>FAILED</b>: 任务执行失败</li>
 *   <li><b>FINALIZE</b>: 任务终结（清理资源）</li>
 * </ul>
 * 
 * <h3>工作模式</h3>
 * <p>采用生产者-消费者模式：
 * <ul>
 *   <li><b>生产者</b>: 主线程池（mainExecutorThreadPool），单线程，每50ms轮询一次</li>
 *   <li><b>消费者</b>: 工作线程池（workerExecutorThreadPool），多线程（CPU核心数），异步处理事件</li>
 * </ul>
 * 
 * <h3>实现类</h3>
 * <ul>
 *   <li>{@link org.apache.dolphinscheduler.server.worker.executor.PhysicalTaskExecutorEventBusCoordinator PhysicalTaskExecutorEventBusCoordinator} - Worker节点使用</li>
 *   <li>{@link org.apache.dolphinscheduler.server.master.engine.executor.LogicTaskExecutorEventBusCoordinator LogicTaskExecutorEventBusCoordinator} - Master节点使用</li>
 * </ul>
 * 
 * @see org.apache.dolphinscheduler.task.executor.eventbus.ITaskExecutorEventBusCoordinator 接口定义
 * @see org.apache.dolphinscheduler.task.executor.listener.ITaskExecutorLifecycleEventListener 生命周期事件监听器接口
 */
@Slf4j
public class TaskExecutorEventBusCoordinator implements ITaskExecutorEventBusCoordinator {

    private final String coordinatorName;

    private final ITaskExecutorRepository taskExecutorRepository;

    private final List<ITaskExecutorLifecycleEventListener> taskExecutorLifecycleEventListeners;

    private static final int DEFAULT_WORKER_SIZE = Runtime.getRuntime().availableProcessors();

    private static final long DEFAULT_FIRE_INTERVAL = 50;

    private static final Set<Integer> firingTaskExecutorIds = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService mainExecutorThreadPool;

    private ThreadPoolExecutor workerExecutorThreadPool;

    public TaskExecutorEventBusCoordinator(final String coordinatorName,
                                           final ITaskExecutorRepository taskExecutorRepository) {
        this.coordinatorName = coordinatorName;
        this.taskExecutorRepository = taskExecutorRepository;
        this.taskExecutorLifecycleEventListeners = new ArrayList<>();
    }

    /**
     * 启动任务执行器事件总线协调器
     * 
     * <p>该方法负责初始化并启动事件总线的轮询机制，主要包括两个线程池的创建和定时任务的安排：
     * 
     * <h3>1. 主线程池（ScheduledExecutorService）</h3>
     * <ul>
     *   <li><b>用途</b>: 定期轮询所有任务执行器的事件总线，检查是否有待处理的事件</li>
     *   <li><b>线程数</b>: 1个线程（单线程执行，避免并发问题）</li>
     *   <li><b>线程类型</b>: 守护线程（Daemon Thread），JVM关闭时自动退出</li>
     *   <li><b>线程命名</b>: "{coordinatorName}-eventbus-coordinator-main-{序号}"，便于问题排查和监控</li>
     *   <li><b>定时任务</b>: 使用 scheduleWithFixedDelay 方法，固定延迟执行</li>
     *   <li><b>执行间隔</b>: DEFAULT_FIRE_INTERVAL (50毫秒)，即每50ms执行一次 fireTaskExecutorEventBus()</li>
     *   <li><b>初始延迟</b>: 0毫秒，立即开始执行</li>
     * </ul>
     * 
     * <h3>2. 工作线程池（ThreadPoolExecutor）</h3>
     * <ul>
     *   <li><b>用途</b>: 异步处理任务执行器的生命周期事件，避免阻塞主轮询线程</li>
     *   <li><b>线程数</b>: DEFAULT_WORKER_SIZE = Runtime.getRuntime().availableProcessors()（CPU核心数）</li>
     *   <li><b>线程类型</b>: 守护线程（Daemon Thread）</li>
     *   <li><b>线程命名</b>: "{coordinatorName}-eventbus-coordinator-worker-{序号}"</li>
     *   <li><b>工作方式</b>: 主线程池发现事件后，将事件处理任务提交到此线程池异步执行</li>
     * </ul>
     * 
     * <h3>3. 定时任务工作原理</h3>
     * <pre>
     * 主线程池（每50ms执行一次）
     *     ↓
     * fireTaskExecutorEventBus()
     *     ↓
     * 遍历所有任务执行器
     *     ↓
     * 检查是否有待处理事件
     *     ↓
     * 提交到工作线程池异步处理
     *     ↓
     * doFireTaskExecutorEventBus()
     *     ↓
     * 触发事件监听器
     * </pre>
     * 
     * <h3>4. 设计考虑</h3>
     * <ul>
     *   <li><b>固定延迟 vs 固定频率</b>: 使用 scheduleWithFixedDelay 确保上次任务执行完成后才执行下一次，
     *       避免任务堆积。如果使用 scheduleAtFixedRate，可能会因为任务执行时间过长导致并发执行</li>
     *   <li><b>异步处理</b>: 事件处理提交到工作线程池，避免阻塞主轮询线程，提高响应性</li>
     *   <li><b>并发控制</b>: 使用 firingTaskExecutorIds Set 防止同一任务执行器的事件被并发处理</li>
     *   <li><b>TODO</b>: 注释中提到未来可能使用事件分发器（event dispatcher）来控制事件触发条件，
     *       而不是固定时间间隔轮询，这样可以更高效地响应事件</li>
     * </ul>
     * 
     * <h3>5. 调用链</h3>
     * <pre>
     * TaskEngine.start()
     *     ↓
     * TaskExecutorEventBusCoordinator.start() (本方法)
     *     ↓
     * 创建主线程池和工作线程池
     *     ↓
     * 安排定时任务（每50ms执行 fireTaskExecutorEventBus）
     * </pre>
     * 
     * <h3>6. 相关类引用</h3>
     * <ul>
     *   <li><b>被调用位置</b>:
     *     <ul>
     *       <li>{@link org.apache.dolphinscheduler.task.executor.TaskEngine#start() TaskEngine.start()}</li>
     *       <li>在 {@link org.apache.dolphinscheduler.server.worker.executor.PhysicalTaskEngineDelegator#start() PhysicalTaskEngineDelegator.start()} 中通过 TaskEngine 间接调用</li>
     *     </ul>
     *   </li>
     *   <li><b>子类实现</b>:
     *     <ul>
     *       <li>{@link org.apache.dolphinscheduler.server.worker.executor.PhysicalTaskExecutorEventBusCoordinator PhysicalTaskExecutorEventBusCoordinator} - Worker节点使用</li>
     *       <li>{@link org.apache.dolphinscheduler.server.master.engine.executor.LogicTaskExecutorEventBusCoordinator LogicTaskExecutorEventBusCoordinator} - Master节点使用</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * @see #fireTaskExecutorEventBus() 定时任务执行的方法
     * @see #doFireTaskExecutorEventBus(ITaskExecutor) 实际处理事件的方法
     * @see org.apache.dolphinscheduler.task.executor.TaskEngine#start() TaskEngine启动方法
     */
    public void start() {
        // 创建主线程池：用于定期轮询事件总线
        // - 线程数：1个（单线程执行，避免并发问题）
        // - 线程类型：守护线程（Daemon Thread），JVM关闭时自动退出
        // - 线程命名格式："{coordinatorName}-eventbus-coordinator-main-{序号}"
        mainExecutorThreadPool = ThreadUtils.newDaemonScheduledExecutorService(
                coordinatorName + "-eventbus-coordinator-main-%d", 1);
        
        // 安排定时任务：每50ms执行一次 fireTaskExecutorEventBus()
        // - scheduleWithFixedDelay: 固定延迟执行，确保上次任务执行完成后才执行下一次
        // - 初始延迟：0毫秒，立即开始执行
        // - 执行间隔：DEFAULT_FIRE_INTERVAL (50毫秒)
        // - TODO: 未来可能使用事件分发器（event dispatcher）来控制事件触发条件，而不是固定时间间隔轮询
        mainExecutorThreadPool.scheduleWithFixedDelay(
                this::fireTaskExecutorEventBus,  // 定时执行的方法引用
                0,                               // 初始延迟：0毫秒
                DEFAULT_FIRE_INTERVAL,          // 执行间隔：50毫秒
                TimeUnit.MILLISECONDS);          // 时间单位：毫秒

        // 创建工作线程池：用于异步处理任务执行器的生命周期事件
        // - 线程数：DEFAULT_WORKER_SIZE = CPU核心数
        // - 线程类型：守护线程（Daemon Thread）
        // - 线程命名格式："{coordinatorName}-eventbus-coordinator-worker-{序号}"
        // - 用途：主线程池发现事件后，将事件处理任务提交到此线程池异步执行，避免阻塞主轮询线程
        workerExecutorThreadPool = ThreadUtils.newDaemonFixedThreadExecutor(
                coordinatorName + "-eventbus-coordinator-worker-%d", DEFAULT_WORKER_SIZE);
        
        log.info("{} started, worker size: {}", coordinatorName, DEFAULT_WORKER_SIZE);
    }

    @Override
    public void registerTaskExecutorLifecycleEventListener(final ITaskExecutorLifecycleEventListener taskExecutorLifecycleEventListener) {
        checkNotNull(taskExecutorLifecycleEventListener);
        taskExecutorLifecycleEventListeners.add(taskExecutorLifecycleEventListener);
    }

    @Override
    public void close() {
        mainExecutorThreadPool.shutdownNow();
        log.info("{} closed", coordinatorName);
    }

    /**
     * 定时任务执行方法：轮询所有任务执行器的事件总线
     * 
     * <p>该方法由主线程池（mainExecutorThreadPool）每50ms调用一次，负责发现待处理的事件并提交到工作线程池异步处理。
     * 
     * <h3>两个线程池的协作关系</h3>
     * 
     * <h4>1. 主线程池（mainExecutorThreadPool）</h4>
     * <ul>
     *   <li><b>类型</b>: ScheduledExecutorService（定时任务线程池）</li>
     *   <li><b>线程数</b>: 1个线程（单线程执行）</li>
     *   <li><b>职责</b>: 
     *     <ul>
     *       <li>定期轮询（每50ms）所有任务执行器的事件总线</li>
     *       <li>发现待处理的事件</li>
     *       <li>将事件处理任务提交到工作线程池（不阻塞，立即返回）</li>
     *       <li>继续轮询下一个任务执行器</li>
     *     </ul>
     *   </li>
     *   <li><b>特点</b>: 轻量级、快速、不阻塞</li>
     * </ul>
     * 
     * <h4>2. 工作线程池（workerExecutorThreadPool）</h4>
     * <ul>
     *   <li><b>类型</b>: ThreadPoolExecutor（固定大小线程池）</li>
     *   <li><b>线程数</b>: CPU核心数（充分利用多核CPU）</li>
     *   <li><b>职责</b>:
     *     <ul>
     *       <li>接收主线程池提交的事件处理任务</li>
     *       <li>异步执行事件处理逻辑（doFireTaskExecutorEventBus）</li>
     *       <li>触发事件监听器，执行业务逻辑（可能包括数据库操作、RPC调用等耗时操作）</li>
     *     </ul>
     *   </li>
     *   <li><b>特点</b>: 多线程并发、处理耗时操作</li>
     * </ul>
     * 
     * <h3>协作模式：生产者-消费者模式</h3>
     * <pre>
     * ┌─────────────────────────────────────────────────────────────┐
     * │  主线程池（mainExecutorThreadPool）- 生产者                 │
     * │  ┌───────────────────────────────────────────────────────┐  │
     * │  │ 每50ms执行一次 fireTaskExecutorEventBus()            │  │
     * │  │   ↓                                                    │  │
     * │  │  遍历所有任务执行器                                    │  │
     * │  │   ↓                                                    │  │
     * │  │  发现事件 → 提交到工作线程池（非阻塞）                 │  │
     * │  │   ↓                                                    │  │
     * │  │  继续轮询下一个任务执行器                              │  │
     * │  └───────────────────────────────────────────────────────┘  │
     * └─────────────────────────────────────────────────────────────┘
     *                         ↓ 提交任务（异步）
     * ┌─────────────────────────────────────────────────────────────┐
     * │  工作线程池（workerExecutorThreadPool）- 消费者             │
     * │  ┌───────────────────────────────────────────────────────┐  │
     * │  │  线程1: 处理任务执行器A的事件                          │  │
     * │  │  线程2: 处理任务执行器B的事件                          │  │
     * │  │  线程3: 处理任务执行器C的事件                          │  │
     * │  │  ...                                                   │  │
     * │  │  线程N: 处理任务执行器X的事件                          │  │
     * │  └───────────────────────────────────────────────────────┘  │
     * └─────────────────────────────────────────────────────────────┘
     * </pre>
     * 
     * <h3>设计优势</h3>
     * <ul>
     *   <li><b>解耦</b>: 轮询和事件处理分离，职责清晰</li>
     *   <li><b>非阻塞</b>: 主线程池不会被耗时的事件处理阻塞，保证轮询的及时性</li>
     *   <li><b>并发处理</b>: 多个任务执行器的事件可以并发处理，提高吞吐量</li>
     *   <li><b>资源隔离</b>: 主线程池专注于发现事件，工作线程池专注于处理事件</li>
     *   <li><b>可扩展</b>: 可以通过调整工作线程池大小来适应不同的负载</li>
     * </ul>
     * 
     * <h3>执行流程</h3>
     * <pre>
     * 主线程池（每50ms）
     *     ↓
     * fireTaskExecutorEventBus() - 本方法
     *     ↓
     * 获取所有任务执行器
     *     ↓
     * 遍历每个任务执行器
     *     ↓
     * 检查是否正在处理（isFiring）
     *     ↓ 否
     * 提交异步任务到工作线程池
     *     ↓
     * 继续下一个任务执行器（不等待）
     *     ↓
     * 工作线程池异步处理事件
     * </pre>
     * 
     * <h3>并发控制机制</h3>
     * <ul>
     *   <li><b>firingTaskExecutorIds</b>: 使用 ConcurrentHashMap.newKeySet() 存储正在处理的任务执行器ID</li>
     *   <li><b>检查机制</b>: 在处理前检查 isFiring()，如果正在处理则跳过</li>
     *   <li><b>标记机制</b>: 使用 CompletableFuture 链式调用，在处理前标记，处理后清理</li>
     *   <li><b>线程安全</b>: 所有操作都是线程安全的，支持多线程并发访问</li>
     * </ul>
     * 
     * @see #doFireTaskExecutorEventBus(ITaskExecutor) 实际处理事件的方法
     * @see #isFiring(ITaskExecutor) 检查是否正在处理的方法
     */
    private void fireTaskExecutorEventBus() {
        try {

            final Collection<ITaskExecutor> taskExecutors = taskExecutorRepository.getAll();
            if (CollectionUtils.isEmpty(taskExecutors)) {
                return;
            }
            for (final ITaskExecutor taskExecutor : taskExecutors) {
                // 检查当前任务执行器是否正在处理事件，避免并发处理同一任务执行器的事件
                // 如果正在处理，跳过本次轮询，等待下次（50ms后）再检查
                if (isFiring(taskExecutor)) {
                    continue;
                }
                final Integer taskExecutorId = taskExecutor.getId();
                
                // 使用 CompletableFuture 链式调用实现异步事件处理，包含三个阶段：
                // 
                // 阶段1 (runAsync): 在工作线程池中执行，将 taskExecutorId 添加到 firingTaskExecutorIds 集合
                //   - 目的: 并发控制，防止同一任务执行器的事件被多个线程并发处理
                //   - 线程安全: firingTaskExecutorIds 是 ConcurrentHashMap.newKeySet()，线程安全
                //
                // 阶段2 (thenAccept): 阶段1完成后，调用 doFireTaskExecutorEventBus(taskExecutor) 处理事件
                //   - 功能: 从事件总线取出事件，根据事件类型调用相应的监听器方法，触发业务逻辑处理
                //   - 执行线程: 继续使用 workerExecutorThreadPool（与阶段1相同的线程或线程池中的其他线程）
                //
                // 阶段3 (whenComplete): 无论阶段2成功还是失败，都执行清理操作
                //   - 操作: 从 firingTaskExecutorIds 集合中移除 taskExecutorId
                //   - 目的: 确保资源清理，防止任务执行器被永久标记为"正在处理"状态
                //   - 参数: v=阶段2返回值(Void), e=异常对象(成功时为null)
                //
                // 执行流程:
                //   主线程池(每50ms) -> 发现事件 -> 提交到工作线程池 -> [阶段1:标记] -> [阶段2:处理] -> [阶段3:清理]
                //
                // 设计优势:
                //   - 非阻塞: 主轮询线程不被阻塞，可继续轮询其他任务执行器
                //   - 并发控制: 通过 firingTaskExecutorIds 防止同一任务执行器的事件并发处理
                //   - 资源清理: whenComplete 确保无论成功/失败都会清理标记，避免死锁
                //   - 异常隔离: 单个任务执行器的事件处理异常不影响其他任务执行器
                //   - 线程池复用: 使用工作线程池处理事件，充分利用多核CPU
                CompletableFuture
                        // 阶段1: 在工作线程池中异步执行，将任务执行器ID添加到"正在处理"集合
                        // 使用 workerExecutorThreadPool 而不是默认的 ForkJoinPool，确保使用我们配置的线程池
                        .runAsync(() -> firingTaskExecutorIds.add(taskExecutorId), workerExecutorThreadPool)
                        // 阶段2: 阶段1完成后，处理任务执行器的事件
                        // v 是阶段1的返回值（Void类型，通常为null），这里不使用
                        .thenAccept(v -> doFireTaskExecutorEventBus(taskExecutor))
                        // 阶段3: 无论阶段2成功还是失败，都执行清理操作
                        // v: 阶段2的返回值（Void类型）
                        // e: 如果阶段2抛出异常，e为异常对象；否则为null
                        .whenComplete((v, e) -> firingTaskExecutorIds.remove(taskExecutorId));
            }
        } catch (Throwable throwable) {
            log.error("Fire TaskExecutorEventBus error", throwable);
        }
    }

    /**
     * 处理任务执行器的事件总线，触发生命周期事件监听器
     * 
     * <p>这是事件处理的最终执行方法，由工作线程池异步调用。该方法负责：
     * <ol>
     *   <li>从任务执行器的事件总线中取出一个生命周期事件（FIFO队列，先进先出）</li>
     *   <li>根据事件类型匹配相应的监听器方法</li>
     *   <li>遍历所有注册的监听器，依次触发处理</li>
     *   <li>记录处理结果（成功或失败）</li>
     * </ol>
     * 
     * <h3>处理流程</h3>
     * <pre>
     * 1. 设置MDC上下文（用于日志追踪）
     * 2. 获取任务执行器的事件总线
     * 3. 检查事件总线是否为空，为空则直接返回
     * 4. 从事件总线中取出一个事件（poll操作，FIFO）
     * 5. 根据事件类型（switch-case）匹配相应的监听器方法
     * 6. 遍历所有注册的监听器，依次调用对应的处理方法
     * 7. 记录处理结果日志
     * </pre>
     * 
     * <h3>事件类型与监听器方法映射</h3>
     * <table border="1">
     *   <tr><th>事件类型</th><th>监听器方法</th><th>说明</th></tr>
     *   <tr><td>DISPATCHED</td><td>onTaskExecutorDispatchedLifecycleEvent</td><td>任务已分发</td></tr>
     *   <tr><td>RUNNING</td><td>onTaskExecutorStartedLifecycleEvent</td><td>任务开始执行</td></tr>
     *   <tr><td>RUNTIME_CONTEXT_CHANGE</td><td>onTaskExecutorRuntimeContextChangedEvent</td><td>运行时上下文变更</td></tr>
     *   <tr><td>PAUSE</td><td>onTaskExecutorPauseLifecycleEvent</td><td>暂停请求</td></tr>
     *   <tr><td>PAUSED</td><td>onTaskExecutorPausedLifecycleEvent</td><td>任务已暂停</td></tr>
     *   <tr><td>KILL</td><td>onTaskExecutorKillLifecycleEvent</td><td>杀死请求</td></tr>
     *   <tr><td>KILLED</td><td>onTaskExecutorKilledLifecycleEvent</td><td>任务已杀死</td></tr>
     *   <tr><td>SUCCESS</td><td>onTaskExecutorSuccessLifecycleEvent</td><td>任务执行成功</td></tr>
     *   <tr><td>FAILED</td><td>onTaskExecutorFailLifecycleEvent</td><td>任务执行失败</td></tr>
     *   <tr><td>FINALIZE</td><td>onTaskExecutorFinalizeLifecycleEvent</td><td>任务终结</td></tr>
     * </table>
     * 
     * <h3>监听器处理</h3>
     * <ul>
     *   <li><b>多监听器支持</b>: 一个事件可以被多个监听器处理，遍历所有注册的监听器</li>
     *   <li><b>监听器职责</b>: 监听器负责具体的业务逻辑，如：
     *     <ul>
     *       <li>更新数据库中的任务实例状态</li>
     *       <li>发送RPC响应给Master节点</li>
     *       <li>报告任务状态变更</li>
     *       <li>触发告警通知</li>
     *     </ul>
     *   </li>
     *   <li><b>异常处理</b>: 单个监听器的异常不会影响其他监听器的执行，异常会被捕获并记录日志</li>
     * </ul>
     * 
     * <h3>注意事项</h3>
     * <ul>
     *   <li>每次只处理一个事件（poll操作），确保事件按顺序处理</li>
     *   <li>使用MDC（Mapped Diagnostic Context）设置日志上下文，便于问题排查</li>
     *   <li>事件处理是异步的，不会阻塞主轮询线程</li>
     *   <li>如果事件总线为空或取不出事件，直接返回，等待下次轮询</li>
     * </ul>
     *
     * @param taskExecutor 任务执行器实例，包含事件总线
     * @see #fireTaskExecutorEventBus() 轮询方法，调用本方法
     * @see org.apache.dolphinscheduler.task.executor.listener.ITaskExecutorLifecycleEventListener 监听器接口
     * @see org.apache.dolphinscheduler.task.executor.events.ITaskExecutorLifecycleEvent 生命周期事件接口
     */
    private void doFireTaskExecutorEventBus(final ITaskExecutor taskExecutor) {
        // 设置MDC上下文，用于日志追踪（包含任务执行器ID、名称等信息）
        try (final TaskExecutorMDCUtils.MDCAutoClosable ignored = TaskExecutorMDCUtils.logWithMDC(taskExecutor)) {
            // 获取任务执行器的事件总线（每个任务执行器都有自己的事件总线）
            final TaskExecutorEventBus taskExecutorEventBus = taskExecutor.getTaskExecutorEventBus();
            
            // 如果事件总线为空，直接返回，等待下次轮询
            if (taskExecutorEventBus.isEmpty()) {
                return;
            }
            
            // 从事件总线中取出一个事件（FIFO队列，先进先出）
            // 注意：每次只取一个事件，确保事件按顺序处理
            Optional<AbstractTaskExecutorLifecycleEvent> headEventOptional = taskExecutorEventBus.poll();
            if (!headEventOptional.isPresent()) {
                // 并发情况下，可能其他线程已经取走了事件，直接返回
                return;
            }
            
            // 获取事件对象
            final ITaskExecutorLifecycleEvent taskExecutorLifecycleEvent = headEventOptional.get();
            try {
                // 遍历所有注册的监听器，根据事件类型调用相应的处理方法
                // 支持多个监听器处理同一事件（如：一个监听器更新数据库，另一个监听器发送RPC响应）
                for (final ITaskExecutorLifecycleEventListener taskExecutorLifecycleEventListener : taskExecutorLifecycleEventListeners) {
                    // 根据事件类型匹配相应的监听器方法，触发对应的业务逻辑处理
                    switch (taskExecutorLifecycleEvent.getType()) {
                        // 任务已分发到容器 -> 通知master
                        case DISPATCHED:
                            taskExecutorLifecycleEventListener.onTaskExecutorDispatchedLifecycleEvent(
                                    ((TaskExecutorDispatchedLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务开始执行 -> 通知master
                        case RUNNING:
                            taskExecutorLifecycleEventListener.onTaskExecutorStartedLifecycleEvent(
                                    ((TaskExecutorStartedLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 运行时上下文变更（如应用链接更新） -> 通知master
                        case RUNTIME_CONTEXT_CHANGE:
                            taskExecutorLifecycleEventListener.onTaskExecutorRuntimeContextChangedEvent(
                                    ((TaskExecutorRuntimeContextChangedLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 暂停请求
                        case PAUSE:
                            taskExecutorLifecycleEventListener.onTaskExecutorPauseLifecycleEvent(
                                    ((TaskExecutorPauseLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务已暂停
                        case PAUSED:
                            taskExecutorLifecycleEventListener.onTaskExecutorPausedLifecycleEvent(
                                    ((TaskExecutorPausedLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 杀死请求
                        case KILL:
                            taskExecutorLifecycleEventListener.onTaskExecutorKillLifecycleEvent(
                                    ((TaskExecutorKillLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务已杀死
                        case KILLED:
                            taskExecutorLifecycleEventListener.onTaskExecutorKilledLifecycleEvent(
                                    ((TaskExecutorKilledLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务执行成功
                        case SUCCESS:
                            taskExecutorLifecycleEventListener.onTaskExecutorSuccessLifecycleEvent(
                                    ((TaskExecutorSuccessLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务执行失败
                        case FAILED:
                            taskExecutorLifecycleEventListener.onTaskExecutorFailLifecycleEvent(
                                    ((TaskExecutorFailedLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 任务终结（清理资源）
                        case FINALIZE:
                            taskExecutorLifecycleEventListener.onTaskExecutorFinalizeLifecycleEvent(
                                    ((TaskExecutorFinalizeLifecycleEvent) taskExecutorLifecycleEvent));
                            break;
                        // 不支持的事件类型，抛出异常
                        default:
                            throw new IllegalArgumentException(
                                    "Unsupported TaskExecutorLifecycleEvent: " + taskExecutorLifecycleEvent);
                    }
                }
                // 所有监听器处理完成后，记录成功日志
                log.info("Success fire {}: {} ",
                        taskExecutorLifecycleEvent.getClass().getSimpleName(),
                        JSONUtils.toPrettyJsonString(taskExecutorLifecycleEvent));
            } catch (Exception e) {
                // 如果事件处理过程中发生异常，记录错误日志
                // 注意：异常不会影响其他任务执行器的事件处理，也不会影响其他监听器的执行
                log.error("Fire TaskExecutorLifecycleEvent: {} error", taskExecutorLifecycleEvent, e);
            }
        }
    }

    private boolean isFiring(final ITaskExecutor taskExecutor) {
        return firingTaskExecutorIds.contains(taskExecutor.getId());
    }

}
