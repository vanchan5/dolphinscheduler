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

import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.annotations.VisibleForTesting;

@Slf4j
@Component
public class WorkflowEventBusFireWorkers implements AutoCloseable {

    @Autowired
    private List<ILifecycleEventHandler> eventHandlers;

    @Autowired
    private MasterConfig masterConfig;

    private static final long DEFAULT_FIRE_INTERVAL = 100;

    private WorkflowEventBusFireWorker[] workflowEventBusFireWorkers;

    private ScheduledExecutorService workflowEventBusFireThreadPool;

    /**
     * 启动工作流事件总线触发工作器
     * 
     * 该方法负责初始化并启动工作流事件总线的触发机制，主要包括：
     * 1. 创建定时任务线程池（ScheduledExecutorService）
     * 2. 创建多个 WorkflowEventBusFireWorker 实例（每个实例对应一个线程）
     * 3. 为每个 Worker 注册事件处理器
     * 4. 为每个 Worker 安排定时任务，定期触发事件处理
     * 
     * 线程池设计说明：
     * - 使用 ScheduledExecutorService 实现定时任务调度
     * - 线程数量由配置项 workflowEventBusFireThreadCount 决定（默认：CPU核心数 * 2 + 1）
     * - 使用守护线程（Daemon Thread），确保 JVM 关闭时能正常退出
     * - 线程命名格式：ds-workflow-eventbus-worker-{序号}，便于问题排查和监控
     * 
     * 定时任务机制：
     * - 使用 scheduleWithFixedDelay 方法，固定延迟执行
     * - 初始延迟：DEFAULT_FIRE_INTERVAL (100ms)
     * - 执行间隔：DEFAULT_FIRE_INTERVAL (100ms)
     * - 执行逻辑：每次执行时调用 fireAllRegisteredEvent() 方法，触发所有已注册工作流的事件
     * 
     * 注意：
     * - scheduleWithFixedDelay 与 scheduleAtFixedRate 的区别：
     *   * scheduleWithFixedDelay：上次任务执行完成后，延迟固定时间再执行下一次
     *   * scheduleAtFixedRate：按固定频率执行，如果上次任务执行时间过长，可能会并发执行
     * - 当前实现使用固定间隔，TODO 中提到未来可能改为使用 wait/notify 机制控制触发间隔
     */
    public void start() {
        /**
         * 从配置中获取工作流事件总线触发线程数量
         * 默认值：Runtime.getRuntime().availableProcessors() * 2 + 1
         * 这个公式通常用于计算适合的线程数，考虑 CPU 密集型任务和 I/O 等待
         */
        final int workflowEventBusFireThreadCount = masterConfig.getWorkflowEventBusFireThreadCount();
        
        /**
         * 创建定时任务线程池
         * ScheduledExecutorService 是 ExecutorService 的子接口，支持延迟执行和周期性执行
         * 
         * 参数说明：
         * - workflowEventBusFireThreadCount: 核心线程数，也是最大线程数（ScheduledThreadPoolExecutor 的特性）
         * - ThreadUtils.newDaemonThreadFactory: 创建守护线程工厂
         *   * 守护线程：当 JVM 中只剩下守护线程时，JVM 会自动退出
         *   * 线程命名：使用 "ds-workflow-eventbus-worker-%d" 格式，%d 会被替换为线程序号
         *   * 异常处理：使用 DefaultUncaughtExceptionHandler 处理未捕获异常
         * 
         * ========== 守护线程 vs 非守护线程详解 ==========
         * 
         * 【守护线程（Daemon Thread）】
         * 1. 定义：守护线程是为其他线程提供服务的后台线程
         * 2. JVM 退出规则：当 JVM 中只剩下守护线程时，JVM 会立即退出，不会等待守护线程执行完成
         * 3. 设置方式：通过 Thread.setDaemon(true) 设置，必须在 start() 之前调用
         * 4. 特点：
         *    - 优先级较低，通常用于后台任务
         *    - 不会阻止 JVM 退出
         *    - 适合执行周期性、监控性任务
         * 
         * 【非守护线程（User Thread / Non-Daemon Thread）】
         * 1. 定义：用户线程是应用程序的主要工作线程
         * 2. JVM 退出规则：JVM 会等待所有非守护线程执行完成后才退出
         * 3. 默认类型：Java 中创建的线程默认都是非守护线程
         * 4. 特点：
         *    - 优先级较高，用于执行核心业务逻辑
         *    - 会阻止 JVM 退出，直到线程结束
         *    - 适合执行关键业务任务
         * 
         * 【为什么在此场景使用守护线程？】
         * 1. 后台服务特性：
         *    - WorkflowEventBusFireWorkers 是后台事件处理服务，不是核心业务线程
         *    - 它定期轮询并触发事件，属于辅助性服务
         * 
         * 2. 优雅关闭保证：
         *    - 虽然实现了 AutoCloseable，在 close() 方法中会调用 shutdown() 优雅关闭
         *    - 但如果应用异常退出（如 kill -9、系统崩溃），可能无法执行 close() 方法
         *    - 使用守护线程可以确保即使没有正常关闭，JVM 也能退出，避免"僵尸进程"
         * 
         * 3. 资源清理：
         *    - 当主程序（MasterServer）关闭时，这些后台线程不应该阻止 JVM 退出
         *    - 守护线程会在 JVM 退出时被强制终止，不会造成资源泄漏
         * 
         * 4. 实际场景对比：
         *    【使用非守护线程的问题】
         *    - 如果线程池的 shutdown() 调用失败或超时，线程可能仍在运行
         *    - 这些线程会阻止 JVM 退出，导致应用无法正常关闭
         *    - 需要手动 kill -9 强制杀死进程，可能造成数据不一致
         * 
         *    【使用守护线程的优势】
         *    - 即使 shutdown() 未完成，JVM 退出时守护线程会被自动终止
         *    - 不会阻止应用正常关闭流程
         *    - 在容器化部署（如 Kubernetes）中，可以更优雅地处理 Pod 终止
         * 
         * 5. 注意事项：
         *    - 守护线程被强制终止时，不会执行 finally 块中的清理代码
         *    - 因此，重要的资源清理逻辑应该在 close() 方法中显式处理
         *    - 当前实现中，close() 方法会调用 shutdown() 进行优雅关闭，这是最佳实践
         * 
         * 【总结】
         * 对于后台服务线程（如定时任务、监控线程、事件处理线程），使用守护线程是标准做法。
         * 它确保了应用可以正常退出，同时通过显式的 close() 方法保证资源的优雅释放。
         */
        workflowEventBusFireThreadPool = Executors.newScheduledThreadPool(
                workflowEventBusFireThreadCount,
                ThreadUtils.newDaemonThreadFactory("ds-workflow-eventbus-worker-%d"));
        
        /**
         * 初始化 Worker 数组，用于存储所有工作流事件总线触发工作器
         * 数组大小等于线程数，每个 Worker 对应一个线程
         */
        workflowEventBusFireWorkers = new WorkflowEventBusFireWorker[workflowEventBusFireThreadCount];

        /**
         * 为每个线程创建一个 Worker 实例，并安排定时任务
         */
        for (int i = 0; i < workflowEventBusFireThreadCount; i++) {
            /**
             * 创建新的 Worker 实例
             * WorkflowEventBusFireWorker 负责：
             * - 管理已注册的工作流执行任务（IWorkflowExecutionRunnable）
             * - 管理事件处理器映射（ILifecycleEventHandler）
             * - 触发工作流事件总线中的事件
             */
            final WorkflowEventBusFireWorker workflowEventBusFireWorker = new WorkflowEventBusFireWorker();
            
            /**
             * 为当前 Worker 注册所有事件处理器
             * eventHandlers 是通过 Spring @Autowired 注入的 ILifecycleEventHandler 列表
             * 每个事件处理器负责处理特定类型的工作流生命周期事件（如启动、完成、失败等）
             * 使用 forEach 和方法引用（::registerEventHandler）简化代码
             */
            eventHandlers.forEach(workflowEventBusFireWorker::registerEventHandler);
            
            /**
             * 将 Worker 存储到数组中，后续可以通过 workerSlot 索引获取对应的 Worker
             */
            workflowEventBusFireWorkers[i] = workflowEventBusFireWorker;

            /**
             * 为当前 Worker 安排定时任务
             * 
             * scheduleWithFixedDelay 方法说明：
             * - 参数1（Runnable command）：要执行的任务，这里使用方法引用 fireAllRegisteredEvent
             * - 参数2（long initialDelay）：初始延迟时间，100ms 后首次执行
             * - 参数3（long delay）：执行间隔，每次执行完成后延迟 100ms 再执行下一次
             * - 参数4（TimeUnit unit）：时间单位，MILLISECONDS（毫秒）
             * 
             * 执行流程：
             * 1. 线程池中的线程会定期调用 fireAllRegisteredEvent() 方法
             * 2. fireAllRegisteredEvent() 会遍历所有已注册的工作流执行任务
             * 3. 对于每个有未处理事件的工作流，从事件总线中取出事件并触发相应的处理器
             * 4. 事件处理器会根据事件类型执行相应的业务逻辑（如状态转换、任务调度等）
             * 
             * TODO 优化建议：
             * 当前使用固定间隔（100ms），无论是否有事件都会执行
             * 未来可以改为使用 wait/notify 机制，让 Worker 在有事件时才被唤醒，提高效率
             */
            workflowEventBusFireThreadPool.scheduleWithFixedDelay(
                    workflowEventBusFireWorker::fireAllRegisteredEvent,
                    DEFAULT_FIRE_INTERVAL,
                    // todo: do not use a fixed interval for all worker, each worker use wait notify to control the fire
                    // interval
                    DEFAULT_FIRE_INTERVAL,
                    TimeUnit.MILLISECONDS);
        }
        log.info("WorkflowEventBusFireWorkers started, worker size: {}", workflowEventBusFireThreadCount);
    }

    public WorkflowEventBusFireWorker getWorker(Integer workerSlot) {
        return workflowEventBusFireWorkers[workerSlot];
    }

    @VisibleForTesting
    public WorkflowEventBusFireWorker[] getWorkers() {
        return workflowEventBusFireWorkers;
    }

    public int getWorkerSize() {
        return masterConfig.getWorkflowEventBusFireThreadCount();
    }

    @Override
    public void close() throws Exception {
        if (workflowEventBusFireThreadPool != null) {
            workflowEventBusFireThreadPool.shutdown();
        }
        log.info("WorkflowEventBusFireWorkers closed");
    }
}
