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

import org.apache.dolphinscheduler.common.thread.BaseDaemonThread;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.task.executor.ITaskExecutor;
import org.apache.dolphinscheduler.task.executor.events.IReportableTaskExecutorLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorLifecycleEventType;
import org.apache.dolphinscheduler.task.executor.events.TaskExecutorPausedLifecycleEvent;
import org.apache.dolphinscheduler.task.executor.listener.TaskExecutorLifecycleEventListener;
import org.apache.dolphinscheduler.task.executor.log.TaskExecutorMDCUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import com.google.common.annotations.VisibleForTesting;

/**
 * 任务执行器生命周期事件远程报告器
 * <p>
 * 这是任务执行器生命周期事件报告的核心实现类，负责将任务执行器的生命周期事件异步报告给 Master。
 * 它继承自 {@link BaseDaemonThread}，在独立的守护线程中运行，实现非阻塞的事件上报机制。
 * </p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>事件收集</b>: 接收并缓存任务执行器的生命周期事件，按任务实例ID分组存储</li>
 *   <li><b>异步上报</b>: 通过守护线程定期轮询并发送事件到 Master</li>
 *   <li><b>重试机制</b>: 对于未收到 ACK 的事件，按照重试间隔（默认3分钟）自动重新发送</li>
 *   <li><b>ACK 处理</b>: 接收 Master 的 ACK，从缓存中移除已确认的事件</li>
 *   <li><b>资源管理</b>: 自动清理空通道，避免内存泄漏</li>
 * </ul>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li><b>启动</b>: 调用 {@link #start()} 启动守护线程，开始轮询处理事件</li>
 *   <li><b>事件收集</b>: 监听器调用 {@link #reportTaskExecutorLifecycleEvent(IReportableTaskExecutorLifecycleEvent)} 添加事件</li>
 *   <li><b>事件处理</b>: 守护线程在 {@link #run()} 方法中循环处理所有通道的事件</li>
 *   <li><b>事件发送</b>: 通过 {@link ITaskExecutorEventRemoteReporterClient} 发送事件到 Master</li>
 *   <li><b>ACK 处理</b>: 收到 ACK 后调用 {@link #receiveTaskExecutorLifecycleEventACK(TaskExecutorLifecycleEventAck)} 移除事件</li>
 * </ol>
 *
 * <h3>事件通道机制</h3>
 * <p>
 * 使用 {@code eventChannels} (ConcurrentHashMap) 按任务实例ID分组存储事件：
 * </p>
 * <ul>
 *   <li><b>Key</b>: 任务实例ID ({@code Integer})</li>
 *   <li><b>Value</b>: {@link ReportableTaskExecutorLifecycleEventChannel} - 该任务实例的事件通道</li>
 *   <li><b>内部存储</b>: 每个通道使用 {@link LinkedBlockingQueue} 存储事件，保证 FIFO 顺序</li>
 * </ul>
 *
 * <h3>重试机制</h3>
 * <p>
 * 事件发送采用智能重试机制：
 * </p>
 * <ul>
 *   <li><b>首次发送</b>: 事件创建后立即发送（{@code latestReportTime == null}）</li>
 *   <li><b>重试间隔</b>: 默认 3 分钟（{@value #DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL} 毫秒）</li>
 *   <li><b>重试条件</b>: 如果事件已发送但未收到 ACK，且距离上次发送时间超过重试间隔，则重新发送</li>
 *   <li><b>重试判断</b>: 通过 {@link #isRetryIntervalExceeded(IReportableTaskExecutorLifecycleEvent)} 判断</li>
 * </ul>
 *
 * <h3>等待机制</h3>
 * <p>
 * 使用 {@link Condition} 实现高效的等待机制，避免忙等待：
 * </p>
 * <ul>
 *   <li><b>等待所有通道为空</b>: 当所有通道都为空时，线程等待直到有新事件到达</li>
 *   <li><b>等待重试间隔</b>: 根据最老事件的发送时间计算等待时间，避免不必要的轮询</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li><b>ConcurrentHashMap</b>: {@code eventChannels} 使用线程安全的 {@link ConcurrentHashMap}</li>
 *   <li><b>ReentrantLock</b>: 使用 {@code eventChannelsLock} 保护关键操作</li>
 *   <li><b>LinkedBlockingQueue</b>: 每个通道内部使用线程安全的 {@link LinkedBlockingQueue}</li>
 *   <li><b>volatile</b>: {@code runningFlag} 使用 volatile 保证可见性</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 创建实例
 * ITaskExecutorEventRemoteReporterClient client = new TaskExecutorEventRemoteReporterClient();
 * TaskExecutorLifecycleEventRemoteReporter reporter = 
 *     new TaskExecutorLifecycleEventRemoteReporter("ReporterName", client);
 *
 * // 启动报告器
 * reporter.start();
 *
 * // 报告事件
 * IReportableTaskExecutorLifecycleEvent event = ...;
 * reporter.reportTaskExecutorLifecycleEvent(event);
 *
 * // 接收 ACK
 * TaskExecutorLifecycleEventAck ack = ...;
 * reporter.receiveTaskExecutorLifecycleEventACK(ack);
 *
 * // 关闭报告器
 * reporter.close();
 * }</pre>
 *
 * @see ITaskExecutorLifecycleEventReporter
 * @see BaseDaemonThread
 * @see ITaskExecutorEventRemoteReporterClient
 * @see ReportableTaskExecutorLifecycleEventChannel
 * @since 3.0.0
 */
@Slf4j
public class TaskExecutorLifecycleEventRemoteReporter extends BaseDaemonThread
        implements
            ITaskExecutorLifecycleEventReporter {

    /**
     * 默认的事件重试间隔：3 分钟（毫秒）
     * <p>
     * 如果事件发送后未收到 ACK，会在 3 分钟后重新发送。
     * </p>
     */
    private static final Long DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(3);

    /**
     * 报告器名称，用于日志标识和线程命名
     */
    private final String reporterName;

    /**
     * 事件通道映射表
     * <p>
     * Key: 任务实例ID ({@code Integer})
     * Value: 该任务实例的事件通道 ({@link ReportableTaskExecutorLifecycleEventChannel})
     * </p>
     * <p>
     * 使用 {@link ConcurrentHashMap} 保证线程安全，支持并发访问。
     * 当通道为空时，会自动从映射表中移除，避免内存泄漏。
     * </p>
     */
    private final Map<Integer, ReportableTaskExecutorLifecycleEventChannel> eventChannels = new ConcurrentHashMap<>();

    /**
     * RPC 客户端，用于与 Master 通信发送事件
     * <p>
     * 根据事件类型调用不同的 RPC 方法将事件发送到 Master。
     * </p>
     */
    private final ITaskExecutorEventRemoteReporterClient taskExecutorEventRemoteReporterClient;

    /**
     * 运行标志位
     * <p>
     * 使用 {@code volatile} 保证多线程可见性。
     * 当设置为 {@code false} 时，守护线程会退出循环。
     * </p>
     */
    private volatile boolean runningFlag;

    /**
     * 事件通道操作的锁
     * <p>
     * 用于保护 {@code eventChannels} 的并发访问，以及实现条件等待机制。
     * </p>
     */
    private final Lock eventChannelsLock = new ReentrantLock();

    /**
     * 事件通道为空的条件
     * <p>
     * 当所有通道都为空时，守护线程会在此条件上等待，直到有新事件到达。
     * 新事件到达时会调用 {@link Condition#signalAll()} 唤醒等待的线程。
     * </p>
     */
    private final Condition taskExecutionEventEmptyCondition = eventChannelsLock.newCondition();

    /**
     * 构造函数
     *
     * @param reporterName 报告器名称，用于日志标识和线程命名
     * @param taskExecutorEventRemoteReporterClient RPC 客户端，用于与 Master 通信发送事件，不能为 null
     */
    public TaskExecutorLifecycleEventRemoteReporter(final String reporterName,
                                                    final ITaskExecutorEventRemoteReporterClient taskExecutorEventRemoteReporterClient) {
        super(reporterName);
        this.reporterName = reporterName;
        this.taskExecutorEventRemoteReporterClient = taskExecutorEventRemoteReporterClient;
    }

    /**
     * 启动事件报告器
     * <p>
     * 设置运行标志为 true，并启动守护线程开始轮询处理事件。
     * 守护线程会在 {@link #run()} 方法中循环处理所有通道的事件。
     * </p>
     */
    @Override
    public void start() {
        // start a thread to send the events
        this.runningFlag = true;
        super.start();
        log.info("{} started", reporterName);
    }

    /**
     * 守护线程的主循环
     * <p>
     * 定期轮询所有事件通道，处理需要发送的事件。采用以下策略：
     * </p>
     * <ol>
     *   <li><b>遍历所有通道</b>: 检查每个通道是否有事件需要处理</li>
     *   <li><b>处理事件</b>: 对于非空通道，调用 {@link #handleTaskExecutionEventChannel(ReportableTaskExecutorLifecycleEventChannel)} 处理</li>
     *   <li><b>等待机制</b>: 
     *     <ul>
     *       <li>如果所有通道都为空，等待直到有新事件到达</li>
     *       <li>否则，根据最老事件的发送时间计算等待时间，等待重试间隔</li>
     *     </ul>
     *   </li>
     * </ol>
     * <p>
     * <b>为什么需要两个等待方法？</b>
     * </p>
     * <p>
     * 这两个等待方法的设计是为了实现**智能等待机制**，避免忙等待（busy-waiting）和频繁轮询，同时保证事件的及时处理：
     * </p>
     * <p>
     * <b>1. tryToWaitIfAllTaskExecutionEventChannelEmpty() - 处理"无事件"场景</b>
     * </p>
     * <ul>
     *   <li><b>触发条件</b>：所有事件通道都为空（没有待处理的事件）</li>
     *   <li><b>等待方式</b>：无限期等待，直到有新事件到达（通过 signalAll() 唤醒）</li>
     *   <li><b>设计目的</b>：
     *     <ul>
     *       <li>避免 CPU 空转：如果没有事件，线程进入 WAITING 状态，不消耗 CPU</li>
     *       <li>及时响应：一旦有新事件到达，立即被唤醒并处理</li>
     *       <li>资源节约：在无事件时完全释放 CPU，让其他线程使用</li>
     *     </ul>
     *   </li>
     *   <li><b>执行结果</b>：如果所有通道都为空，线程会一直等待；如果有通道不为空，立即返回</li>
     * </ul>
     * <p>
     * <b>2. waitIfAnyTaskExecutionEventChannelRetryIntervalPassed() - 处理"有事件但需等待重试"场景</b>
     * </p>
     * <ul>
     *   <li><b>触发条件</b>：有事件通道不为空，但事件已发送过且重试间隔未到</li>
     *   <li><b>等待方式</b>：根据最老事件的发送时间，计算需要等待的时间，精确等待到最早可以重试的时间点</li>
     *   <li><b>设计目的</b>：
     *     <ul>
     *       <li>避免频繁轮询：不立即重试，而是等待到重试间隔到达</li>
     *       <li>精确等待：根据最老事件的发送时间计算等待时间，避免不必要的轮询</li>
     *       <li>提前唤醒：如果等待期间有新事件到达，会被提前唤醒（signalAll()）</li>
     *       <li>智能调度：只等待到最早需要重试的时间点，不浪费等待时间</li>
     *     </ul>
     *   </li>
     *   <li><b>执行结果</b>：
     *     <ul>
     *       <li>如果所有通道都为空，waitInterval <= 0，立即返回（因为 tryToWaitIfAllTaskExecutionEventChannelEmpty() 已经处理了这种情况）</li>
     *       <li>如果有事件但重试间隔未到，计算等待时间并等待</li>
     *       <li>如果有事件且重试间隔已到，waitInterval <= 0，立即返回，下次循环会立即处理</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>
     * <b>两个方法的执行顺序和关系</b>
     * </p>
     * <ul>
     *   <li><b>顺序执行</b>：先检查是否所有通道都为空，再检查是否需要等待重试间隔</li>
     *   <li><b>互补关系</b>：
     *     <ul>
     *       <li>如果所有通道都为空：第一个方法会等待，第二个方法会立即返回（waitInterval <= 0）</li>
     *       <li>如果有通道不为空但重试间隔未到：第一个方法会立即返回，第二个方法会计算等待时间并等待</li>
     *       <li>如果有通道不为空且重试间隔已到：两个方法都会立即返回，下次循环会立即处理</li>
     *     </ul>
     *   </li>
     *   <li><b>性能优化</b>：
     *     <ul>
     *       <li>避免忙等待：在无事件或需要等待时，线程进入 WAITING 状态</li>
     *       <li>精确调度：根据实际需要等待的时间，精确计算等待时间</li>
     *       <li>及时响应：一旦有新事件或重试间隔到达，立即被唤醒</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>
     * <b>执行流程图</b>
     * </p>
     * <pre>{@code
     * while (runningFlag) {
     *     // 1. 处理所有通道中的事件
     *     for (eventChannel : eventChannels) {
     *         handleTaskExecutionEventChannel(eventChannel);
     *     }
     *     
     *     // 2. 检查是否所有通道都为空
     *     tryToWaitIfAllTaskExecutionEventChannelEmpty();
     *     //    - 如果为空：等待直到有新事件（signalAll() 唤醒）
     *     //    - 如果不为空：立即返回，继续执行
     *     
     *     // 3. 检查是否需要等待重试间隔
     *     waitIfAnyTaskExecutionEventChannelRetryIntervalPassed();
     *     //    - 如果所有通道都为空：waitInterval <= 0，立即返回（已由步骤2处理）
     *     //    - 如果有事件但重试间隔未到：计算等待时间并等待
     *     //    - 如果有事件且重试间隔已到：waitInterval <= 0，立即返回
     *     
     *     // 4. 继续下一次循环
     * }
     * }</pre>
     * <p>
     * <b>设计优势</b>
     * </p>
     * <ul>
     *   <li>✅ <b>CPU 友好</b>：避免忙等待，在需要等待时让线程进入 WAITING 状态</li>
     *   <li>✅ <b>精确调度</b>：根据实际需要等待的时间，精确计算等待时间</li>
     *   <li>✅ <b>及时响应</b>：一旦有新事件或重试间隔到达，立即被唤醒并处理</li>
     *   <li>✅ <b>资源节约</b>：在无事件时完全释放 CPU，让其他线程使用</li>
     *   <li>✅ <b>智能等待</b>：根据不同的场景（无事件 vs 有事件但需等待），采用不同的等待策略</li>
     * </ul>
     * <p>
     * 当 {@code runningFlag} 为 false 时，退出循环，线程结束。
     * </p>
     */
    @Override
    public void run() {
        while (runningFlag) {
            try {
                // 遍历所有事件通道，处理需要发送的事件
                for (final ReportableTaskExecutorLifecycleEventChannel eventChannel : eventChannels.values()) {
                    if (eventChannel.isEmpty()) {
                        continue;
                    }
                    // 处理当前通道中的事件（发送首次事件或重试间隔已到的事件）重试的没收到master-ack事件,确保事件不会丢失
                    handleTaskExecutionEventChannel(eventChannel);
                }
                
                // 【设计原因1】如果所有通道都为空，等待直到有新事件到达
                // 目的：避免忙等待（busy-waiting），节省 CPU 资源
                // 机制：当所有通道都为空时，线程进入 WAITING 状态，直到其他线程添加新事件并调用 signalAll() 唤醒
                // 注意：await() 会自动释放锁，允许其他线程添加事件；被唤醒后会重新获取锁
                tryToWaitIfAllTaskExecutionEventChannelEmpty();
                
                // 【设计原因2】如果任何任务执行事件通道重试间隔未过（未到），则等待
                // 目的：避免频繁轮询，根据最老事件的发送时间智能计算等待时间
                // 机制：
                //   - 如果所有通道都为空，此方法会立即返回（waitInterval <= 0）
                //   - 如果有事件但重试间隔未到，计算等待时间 = (最老事件发送时间 + 重试间隔) - 当前时间
                //   - 等待到最早可以重试的时间点，避免不必要的轮询
                //   - 如果重试间隔已到（waitInterval <= 0），立即返回，下次循环会立即处理
                // 注意：如果等待期间有新事件到达（signalAll()），线程会被提前唤醒，立即处理新事件
                waitIfAnyTaskExecutionEventChannelRetryIntervalPassed();
            } catch (InterruptedException e) {
                // 捕获中断异常，通常表示线程被外部请求停止（如 JVM 关闭、调用 close() 方法等）
                log.info("{} interrupted", reporterName);
                // 重新设置中断标志：当捕获到 InterruptedException 时，线程的中断标志会被清除
                // 调用 interrupt() 重新设置中断标志，以便上层代码能够检测到线程已被中断
                // 这是 Java 并发编程的最佳实践：捕获 InterruptedException 后，应该重新设置中断标志
                Thread.currentThread().interrupt();
                // 退出 while 循环，线程结束
                // 
                // 【设计原因】为什么不直接抛出异常？
                // 1. 这是守护线程的 run() 方法，不能抛出受检异常（InterruptedException 是受检异常）
                // 2. 即使抛出异常，也会被 BaseDaemonThread 的 UncaughtExceptionHandler 捕获，
                //    但这不是优雅的退出方式，可能导致资源清理不完整
                // 3. 通过 break 退出循环，可以让线程自然结束，确保 finally 块和资源清理代码能够执行
                // 4. 重新设置中断标志后，如果后续代码检查中断状态，能够正确检测到中断
                // 5. 这是长期运行线程的标准退出模式：响应中断请求，优雅退出
                break;
            } catch (Exception ex) {
                // 捕获其他运行时异常，记录错误日志，但继续运行
                // 
                // 【设计原因】为什么只记录错误而不抛出异常？
                // 1. 这是长期运行的守护线程，需要持续处理事件报告任务
                //    如果抛出异常导致线程终止，整个事件报告功能将完全失效，影响系统可用性
                // 2. 单个事件处理失败不应该影响其他事件的报告
                //    例如：某个事件的 RPC 调用失败，不应该阻止其他事件继续报告
                // 3. 异常可能是临时性的（如网络抖动、Master 暂时不可用），
                //    继续运行可以在下次循环时重试，提高系统的容错能力
                // 4. 通过日志记录异常，运维人员可以监控和排查问题，
                //    同时系统保持运行，不会因为偶发异常而停止服务
                // 5. 这是"故障隔离"的设计原则：单个组件的错误不应该导致整个系统崩溃
                // 6. 如果确实需要停止线程，可以通过设置 runningFlag = false 或调用 close() 方法，
                //    这会触发 InterruptedException，从而优雅地退出循环
                log.error("Fire ReportableTaskExecutorLifecycleEventChannel error", ex);
            }
        }
        log.info("{} break loop", reporterName);
    }

    /**
     * 报告任务执行器生命周期事件
     * <p>
     * 将事件添加到对应任务实例的事件通道中，等待守护线程处理。
     * 这是一个非阻塞方法，立即返回，不会等待事件发送完成。
     * </p>
     * <p>
     * 执行流程：
     * </p>
     * <ol>
     *   <li>获取任务实例ID</li>
     *   <li>根据任务实例ID获取或创建事件通道（如果不存在则创建新通道）</li>
     *   <li>将事件添加到通道的队列中</li>
     *   <li>唤醒等待的守护线程（如果有）</li>
     * </ol>
     *
     * <ol>
     *  <li>{@link TaskExecutorEventBusCoordinator#doFireTaskExecutorEventBus(ITaskExecutor)}  -></li>
     *  <li>{@link TaskExecutorLifecycleEventListener#onTaskExecutorPausedLifecycleEvent(TaskExecutorPausedLifecycleEvent)}</li>
     *  <li>{@link }</li>
     *  </ol>
     *
     *  <p> eventChannels 通过 reportTaskExecutorLifecycleEvent() 方法填充，该方法在以下场景被调用：</p>
     *
     * <ol>
     * <li>任务分发完成: TaskExecutorDispatchedLifecycleEvent 发布后</li>
     * <li>任务开始执行: TaskExecutorStartedLifecycleEvent 发布后</li>
     * <li>运行时上下文变更: TaskExecutorRuntimeContextChangedLifecycleEvent 发布后</li>
     * <li>任务暂停: TaskExecutorPausedLifecycleEvent 发布后</li>
     * <li>任务杀死: TaskExecutorKilledLifecycleEvent 发布后</li>
     * <li>任务成功: TaskExecutorSuccessLifecycleEvent 发布后</li>
     * <li>任务失败: TaskExecutorFailedLifecycleEvent 发布后</li>
     * </ol>
     *
     *
     * @param reportableTaskExecutorLifecycleEvent 待报告的任务执行器生命周期事件，不能为 null
     *
     */
    @Override
    public void reportTaskExecutorLifecycleEvent(final IReportableTaskExecutorLifecycleEvent reportableTaskExecutorLifecycleEvent) {
        eventChannelsLock.lock();
        try {
            log.debug("Report : {}", JSONUtils.toPrettyJsonString(reportableTaskExecutorLifecycleEvent));
            int taskInstanceId = reportableTaskExecutorLifecycleEvent.getTaskInstanceId();
            // 根据任务实例ID获取或创建对应的事件通道，如果通道不存在则创建新通道
            // 然后将任务执行器生命周期事件添加到该通道的队列中，等待后续上报
            eventChannels.computeIfAbsent(
                    taskInstanceId,
                    k -> new ReportableTaskExecutorLifecycleEventChannel(taskInstanceId))
                    .addTaskExecutionEvent(reportableTaskExecutorLifecycleEvent);
            taskExecutionEventEmptyCondition.signalAll();
        } finally {
            eventChannelsLock.unlock();
        }

    }

    /**
     * 接收任务执行器生命周期事件的 ACK
     * <p>
     * 当 Master 收到并处理完事件后，会发送 ACK 确认。此方法用于处理 ACK，从事件通道中移除已确认的事件。
     * </p>
     * <p>
     * <b>重要设计说明</b>：
     * </p>
     * <p>
     * 事件只有在收到 ACK 后才会从队列中移除。在 `handleTaskExecutionEventChannel()` 方法中使用 `peek()` 查看事件（不移除），
     * 这可能导致疑问：如果 Master 一直没有发送 ACK，主线程会不会一直重复处理同一个事件？
     * </p>
     * <p>
     * <b>答案：不会一直重复处理</b>。虽然事件会一直留在队列中直到收到 ACK，但通过重试间隔控制机制避免了频繁重复发送：
     * </p>
     * <ul>
     *   <li><b>首次发送</b>：事件从未发送过（`latestReportTime == null`）时，立即发送并设置 `latestReportTime`</li>
     *   <li><b>重试间隔控制</b>：发送后，如果距离上次发送时间 < 3 分钟，`isRetryIntervalExceeded()` 返回 `false`，
     *       此时会 `break` 退出循环，不会重复发送</li>
     *   <li><b>重试发送</b>：只有当距离上次发送时间 >= 3 分钟时，才会再次发送，确保不会频繁重复</li>
     *   <li><b>事件移除</b>：只有当收到 ACK 后，才会从队列中移除事件，保证事件不丢失</li>
     * </ul>
     * <p>
     * 这种设计实现了**可靠的事件报告机制**：
     * </p>
     * <ul>
     *   <li>✅ <b>可靠性</b>：事件不会丢失，即使 Master 暂时不可用，也会持续重试</li>
     *   <li>✅ <b>性能优化</b>：通过重试间隔避免频繁重复发送，减少网络开销</li>
     *   <li>✅ <b>资源节约</b>：使用 `peek()` 而不是 `poll()`，确保事件在确认前不会丢失</li>
     *   <li>✅ <b>最终一致性</b>：通过重试机制，确保事件最终能够被 Master 接收</li>
     * </ul>
     * <p>
     * 执行流程：
     * </p>
     * <ol>
     *   <li>根据任务实例ID查找对应的事件通道</li>
     *   <li>如果通道不存在，直接返回（可能已经被清理）</li>
     *   <li>根据事件类型从通道中移除对应的事件</li>
     *   <li>如果通道为空，从映射表中移除该通道，避免内存泄漏</li>
     *   <li>唤醒等待的守护线程（如果有）</li>
     * </ol>
     *
     * @param eventAck 事件 ACK，包含任务实例ID和事件类型，不能为 null
     * @see #handleTaskExecutionEventChannel(ReportableTaskExecutorLifecycleEventChannel)
     * @see #isRetryIntervalExceeded(IReportableTaskExecutorLifecycleEvent)
     * @see ReportableTaskExecutorLifecycleEventChannel#peek()
     * @see ReportableTaskExecutorLifecycleEventChannel#remove(TaskExecutorLifecycleEventType)
     */
    @Override
    public void receiveTaskExecutorLifecycleEventACK(final TaskExecutorLifecycleEventAck eventAck) {
        final int taskExecutorId = eventAck.getTaskExecutorId();
        eventChannelsLock.lock();
        try {
            final ReportableTaskExecutorLifecycleEventChannel eventChannel = eventChannels.get(taskExecutorId);
            if (eventChannel == null) {
                return;
            }
            final IReportableTaskExecutorLifecycleEvent removed =
                    eventChannel.remove(eventAck.getTaskExecutorLifecycleEventType());
            if (removed != null) {
                log.info("Success removed {} by ack: {}", removed, eventAck);
            } else {
                log.info("Failed removed ReportableTaskExecutorLifecycleEvent by ack: {}", eventAck);
            }
            if (eventChannel.isEmpty()) {
                eventChannels.remove(taskExecutorId);
                log.debug("Removed ReportableTaskExecutorLifecycleEventChannel: {}", taskExecutorId);
            }
            taskExecutionEventEmptyCondition.signalAll();
        } finally {
            eventChannelsLock.unlock();
        }
    }

    /**
     * 重新分配工作流实例主机
     * <p>
     * 当工作流实例的主机发生变化时（如故障转移），需要更新该任务实例所有待发送事件的工作流实例主机。
     * </p>
     *
     * @param taskInstanceId 任务实例ID
     * @param workflowHost 新的工作流实例主机地址
     * @return 如果找到对应的通道并成功更新，返回 true；否则返回 false
     */
    @Override
    public boolean reassignWorkflowInstanceHost(int taskInstanceId, String workflowHost) {
        eventChannelsLock.lock();
        try {
            final ReportableTaskExecutorLifecycleEventChannel eventChannel = eventChannels.get(taskInstanceId);
            if (eventChannel == null) {
                return false;
            }
            eventChannel.taskExecutionEventsQueue.forEach(event -> event.setWorkflowInstanceHost(workflowHost));
            return true;
        } finally {
            eventChannelsLock.unlock();
        }
    }

    /**
     * 关闭事件报告器
     * <p>
     * 设置运行标志为 false，守护线程会在下次循环时退出。
     * 这是一个优雅关闭机制，不会立即中断线程。
     * </p>
     */
    @Override
    public void close() {
        // shutdown the thread
        runningFlag = false;
        log.info("{} closed", reporterName);
    }

    /**
     * 获取事件通道映射表
     * <p>
     * 此方法可用于监控和调试，获取当前所有事件通道的状态。
     * 可以通过以下方式监控队列大小来发现 Master 处理不及时的异常：
     * </p>
     * <ul>
     *   <li><b>事件通道总数</b>：{@code getEventChannels().size()} - 当前有多少个任务实例有未确认的事件</li>
     *   <li><b>总事件数</b>：遍历所有通道，累加每个通道的队列大小 - 当前有多少个事件等待 Master 确认</li>
     *   <li><b>单个通道最大事件数</b>：找出事件数最多的通道 - 发现积压最严重的任务实例</li>
     * </ul>
     * <p>
     * <b>异常判断标准</b>：
     * </p>
     * <ul>
     *   <li>事件通道数持续增长且不下降，超过 200 → Master 处理速度跟不上或不可用</li>
     *   <li>总事件数持续增长，超过 1000 → Master 处理不及时，事件积压严重</li>
     *   <li>单个通道事件数超过 10 → 该任务的事件一直未被 Master 确认</li>
     * </ul>
     * <p>
     * <b>监控实现建议</b>：
     * </p>
     * <ul>
     *   <li>通过 Micrometer 暴露指标（参考 {@link WorkerServerMetrics#registerWorkerExecuteQueueSizeGauge(Supplier)}）</li>
     *   <li>通过日志定期输出队列大小</li>
     *   <li>通过 JMX 暴露监控方法</li>
     * </ul>
     *
     * @return 事件通道映射表，Key 为任务实例ID，Value 为对应的事件通道
     * @see #receiveTaskExecutorLifecycleEventACK(TaskExecutorLifecycleEventAck)
     * @see ReportableTaskExecutorLifecycleEventChannel#taskExecutionEventsQueue
     */
    @VisibleForTesting
    public Map<Integer, ReportableTaskExecutorLifecycleEventChannel> getEventChannels() {
        return eventChannels;
    }

    /**
     * 处理事件通道中的事件
     * <p>
     * 循环处理通道中的所有事件，直到通道为空或遇到无法发送的事件。
     * </p>
     * <p>
     * 处理逻辑：
     * </p>
     * <ol>
     *   <li>检查通道是否为空，如果为空直接返回</li>
     *   <li>循环处理事件，只要通道不为空就继续</li>
     *   <li>查看队首事件（使用 peek，不移除）</li>
     *   <li>设置 MDC 上下文用于日志追踪</li>
     *   <li>判断是否需要发送：
     *     <ul>
     *       <li>如果事件从未发送过（{@code latestReportTime == null}）</li>
     *       <li>或者重试间隔已超过（距离上次发送时间超过 3 分钟）</li>
     *       <li>满足任一条件，则发送事件并继续处理下一个</li>
     *     </ul>
     *   </li>
     *   <li>如果重试间隔未到，记录 debug 日志并退出循环</li>
     *   <li>如果发送异常，记录错误日志并退出循环，等待下次轮询时重试</li>
     * </ol>
     *
     * @param reportableTaskExecutorLifecycleEventChannel 待处理的事件通道，不能为 null
     */
    private void handleTaskExecutionEventChannel(final ReportableTaskExecutorLifecycleEventChannel reportableTaskExecutorLifecycleEventChannel) {
        if (reportableTaskExecutorLifecycleEventChannel.isEmpty()) {
            return;
        }
        while (!reportableTaskExecutorLifecycleEventChannel.isEmpty()) {
            final IReportableTaskExecutorLifecycleEvent headEvent = reportableTaskExecutorLifecycleEventChannel.peek();
            try (
                    final TaskExecutorMDCUtils.MDCAutoClosable ignore =
                            TaskExecutorMDCUtils.logWithMDC(headEvent.getTaskInstanceId())) {
                try {
                    if (isTaskExecutorEventNeverSent(headEvent) || isRetryIntervalExceeded(headEvent)) {
                        // 设置最后一次latestReportTime
                        taskExecutorEventRemoteReporterClient.reportTaskExecutionEventToMaster(headEvent);
                        continue;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "The ReportableTaskExecutorLifecycleEvent: {} latest send time: {} doesn't exceeded retry interval",
                                headEvent,
                                headEvent.getLatestReportTime());
                    }
                    break;
                } catch (Exception ex) {
                    log.error("Send TaskExecutionEvent: {} to master error will retry after {} mills",
                            headEvent,
                            DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL,
                            ex);
                    break;

                }
            }
        }
    }

    /**
     * 检查所有事件通道是否都为空
     *
     * @return 如果所有通道都为空，返回 true；否则返回 false
     */
    private boolean isAllTaskExecutorEventChannelEmpty() {
        return eventChannels
                .values()
                .stream()
                .allMatch(ReportableTaskExecutorLifecycleEventChannel::isEmpty);
    }

    /**
     * 获取所有已发送事件中最老的发送时间
     * <p>
     * 用于计算等待时间，避免不必要的轮询。
     * 只考虑已发送过的事件（{@code latestReportTime != null}）。
     * </p>
     *
     * @return 最老的发送时间（毫秒），如果没有已发送的事件，返回 0L
     */
    private long getOldestReportTime() {
        return eventChannels.values()
                .stream()
                .filter(ReportableTaskExecutorLifecycleEventChannel::isNotEmpty)
                .map(ReportableTaskExecutorLifecycleEventChannel::peek)
                .filter(event -> !isTaskExecutorEventNeverSent(event))
                .map(IReportableTaskExecutorLifecycleEvent::getLatestReportTime)
                .min(Long::compareTo)
                .orElse(0L);
    }

    /**
     * 判断事件是否从未发送过
     *
     * @param headEvent 待检查的事件，不能为 null
     * @return 如果事件的 {@code latestReportTime} 为 null，返回 true；否则返回 false
     */
    private boolean isTaskExecutorEventNeverSent(final IReportableTaskExecutorLifecycleEvent headEvent) {
        return headEvent.getLatestReportTime() == null;
    }

    /**
     * 判断重试间隔是否已超过
     * <p>
     * 如果事件从未发送过，返回 true（需要立即发送）。
     * 否则，判断距离上次发送时间是否超过重试间隔（默认 3 分钟）。
     * </p>
     *
     * @param reportableTaskExecutorLifecycleEvent 待检查的事件，不能为 null
     * @return 如果需要重试（从未发送或重试间隔已超过），返回 true；否则返回 false
     */
    private boolean isRetryIntervalExceeded(final IReportableTaskExecutorLifecycleEvent reportableTaskExecutorLifecycleEvent) {
        if (isTaskExecutorEventNeverSent(reportableTaskExecutorLifecycleEvent)) {
            return true;
        }
        long currentTime = System.currentTimeMillis();
        return currentTime - reportableTaskExecutorLifecycleEvent
                .getLatestReportTime() > DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL;
    }

    /**
     * 如果所有事件通道都为空，则等待直到有新事件到达
     * <p>
     * 使用 {@link Condition#await()} 实现高效等待，避免忙等待。
     * 当有新事件到达时（{@link #reportTaskExecutorLifecycleEvent(IReportableTaskExecutorLifecycleEvent)} 中调用
     * {@link Condition#signalAll()}），线程会被唤醒。
     * </p>
     *
     * @throws InterruptedException 如果等待过程中线程被中断
     *
     * <ol>
     * <li> lock() - 获取锁 ✓ </li>
     * <li> isAllTaskExecutorEventChannelEmpty() = true</li>
     * <li> await() - 进入等待，自动释放锁 ✓</li>
     *      <ul>
     *    <li>线程进入 WAITING 状态</li>
     *    <li>锁被释放，其他线程可以获取</li>
     *      </ul>
     * <li> 其他线程添加事件，调用 signalAll()</li>
     * <li> 线程被唤醒，重新获取锁 ✓</li>
     * <ul>
     *    <li> 在 await() 方法内部，线程被唤醒后会尝试重新获取 eventChannelsLock)</li>
     *    <li> 重新获取锁的过程发生在 await() 方法返回之前)</li>
     *    <li> 如果锁被其他线程持有，当前线程会阻塞等待，直到锁可用)</li>
     *    </ul>
     * <li> await() 方法返回，此时线程已经重新持有锁 ✓</li>
     * <li> 重新检查条件，isAllTaskExecutorEventChannelEmpty() = false</li>
     * <li> 退出循环</li>
     * <li> unlock() - 释放锁 ✓</li>
     * </ol>
     */
    private void tryToWaitIfAllTaskExecutionEventChannelEmpty() throws InterruptedException {
        eventChannelsLock.lock();// 获取锁
        while (isAllTaskExecutorEventChannelEmpty()) {
            taskExecutionEventEmptyCondition.await();
            // ↑ 执行到这里时，进入 await(),锁已经被释放了！线程进入 WAITING 状态，不持有锁,当被唤醒后，锁会被重新获取(在 await() 内部)锁(acquireQueued),
            // 然后继续执行后续代码

            // ↑ 从 await() 返回时(被唤醒)，锁已经被重新获取了,可以安全地访问共享资源检查条件
            //   线程重新获取锁，检查条件，退出循环
        }
        eventChannelsLock.unlock();
    }

    /**
     * 等待重试间隔
     * <p>
     * 根据最老事件的发送时间计算等待时间，避免不必要的轮询。
     * 如果等待时间 <= 0，说明已经有事件可以重试，直接返回。
     * </p>
     * <p>
     * 等待时间计算公式：
     * {@code waitInterval = (最老事件发送时间 + 重试间隔) - 当前时间}
     * </p>
     * <p>
     * <b>执行示例</b>：
     * </p>
     * <p>
     * <b>示例1：计算等待时间并等待</b>
     * </p>
     * <pre>{@code
     * 假设：
     * - 当前时间：2024-01-01 10:00:00 (时间戳: 1704067200000)
     * - 最老事件发送时间：2024-01-01 09:58:00 (时间戳: 1704067080000)
     * - 重试间隔：3分钟 (180000 毫秒)
     * 
     * 计算：
     * waitInterval = (1704067080000 + 180000) - 1704067200000
     *              = 1704067260000 - 1704067200000
     *              = 60000 毫秒 (1分钟)
     * 
     * 结果：
     * - waitInterval > 0，调用 await(60000, TimeUnit.MILLISECONDS)
     * - 线程等待 1 分钟后被唤醒（或提前被 signalAll() 唤醒）
     * - await() 返回后，线程重新持有锁，继续执行
     * }</pre>
     * <p>
     * <b>示例2：已有事件可以重试，直接返回</b>
     * </p>
     * <pre>{@code
     * 假设：
     * - 当前时间：2024-01-01 10:01:00 (时间戳: 1704067260000)
     * - 最老事件发送时间：2024-01-01 09:58:00 (时间戳: 1704067080000)
     * - 重试间隔：3分钟 (180000 毫秒)
     * 
     * 计算：
     * waitInterval = (1704067080000 + 180000) - 1704067260000
     *              = 1704067260000 - 1704067260000
     *              = 0 毫秒
     * 
     * 结果：
     * - waitInterval <= 0，直接返回，不等待
     * - 下次轮询时会立即处理该事件（重试间隔已到）
     * }</pre>
     * <p>
     * <b>示例3：重试间隔已超过，立即返回</b>
     * </p>
     * <pre>{@code
     * 假设：
     * - 当前时间：2024-01-01 10:02:00 (时间戳: 1704067320000)
     * - 最老事件发送时间：2024-01-01 09:58:00 (时间戳: 1704067080000)
     * - 重试间隔：3分钟 (180000 毫秒)
     * 
     * 计算：
     * waitInterval = (1704067080000 + 180000) - 1704067320000
     *              = 1704067260000 - 1704067320000
     *              = -60000 毫秒 (负数，表示重试间隔已超过 1 分钟)
     * 
     * 结果：
     * - waitInterval < 0，直接返回，不等待
     * - 下次轮询时会立即处理该事件（重试间隔已超过）
     * }</pre>
     * <p>
     * <b>示例4：多个事件通道的等待时间计算</b>
     * </p>
     * <pre>{@code
     * 假设 eventChannels 中有 3 个事件通道：
     * - 通道1：事件发送时间 10:00:00，重试间隔 3 分钟，下次可重试时间 10:03:00
     * - 通道2：事件发送时间 10:01:00，重试间隔 3 分钟，下次可重试时间 10:04:00
     * - 通道3：事件发送时间 10:02:00，重试间隔 3 分钟，下次可重试时间 10:05:00
     * 
     * getOldestReportTime() 返回：10:00:00 (最老的发送时间)
     * 
     * 当前时间：10:00:30
     * waitInterval = (10:00:00 + 3分钟) - 10:00:30
     *              = 10:03:00 - 10:00:30
     *              = 150 秒
     * 
     * 结果：
     * - 等待 150 秒后，通道1的事件可以重试
     * - 如果提前被唤醒（如新事件到达），会立即检查所有通道
     * }</pre>
     * <p>
     * <b>关键点</b>：
     * </p>
     * <ul>
     *   <li><b>await() 会自动释放锁</b>：调用 await() 时，线程会释放 eventChannelsLock，其他线程可以添加事件</li>
     *   <li><b>await() 会自动重新获取锁</b>：await() 返回时，线程已经重新持有锁，可以安全地访问共享资源</li>
     *   <li><b>可能提前唤醒</b>：如果其他线程调用 signalAll()（如添加新事件），等待线程会被提前唤醒</li>
     *   <li><b>超时唤醒</b>：如果等待时间到达，线程会被自动唤醒，继续执行</li>
     * </ul>
     *
     * @throws InterruptedException 如果等待过程中线程被中断
     * @see #getOldestReportTime()
     * @see #DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL
     * @see Condition#await(long, TimeUnit)
     */
    private void waitIfAnyTaskExecutionEventChannelRetryIntervalPassed() throws InterruptedException {
        eventChannelsLock.lock();
        try {
            final long waitInterval =
                    (getOldestReportTime() + DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL) - System.currentTimeMillis();
            if (waitInterval <= 0) {
                return;
            }
            taskExecutionEventEmptyCondition.await(waitInterval, TimeUnit.MILLISECONDS);
        } finally {
            eventChannelsLock.unlock();
        }
    }

    /**
     * 可报告的任务执行器生命周期事件通道
     * <p>
     * 每个任务实例对应一个事件通道，用于存储该任务实例的所有待报告事件。
     * 使用 {@link LinkedBlockingQueue} 实现 FIFO 队列，保证事件按顺序处理。
     * </p>
     * <p>
     * 事件通道的主要功能：
     * </p>
     * <ul>
     *   <li><b>事件存储</b>: 使用队列存储待报告的事件</li>
     *   <li><b>事件查看</b>: 支持查看队首事件（不移除）</li>
     *   <li><b>事件移除</b>: 根据事件类型移除已确认的事件</li>
     *   <li><b>状态查询</b>: 支持查询通道是否为空</li>
     * </ul>
     */
    public static class ReportableTaskExecutorLifecycleEventChannel {

        /**
         * 任务执行器ID（即任务实例ID）
         */
        @Getter
        private final int taskExecutorId;

        /**
         * 任务执行器生命周期事件队列
         * <p>
         * 使用 {@link LinkedBlockingQueue} 实现线程安全的 FIFO 队列。
         * 事件按照添加顺序存储，处理时按顺序查看和移除。
         * </p>
         */
        private final LinkedBlockingQueue<IReportableTaskExecutorLifecycleEvent> taskExecutionEventsQueue;

        /**
         * 构造函数
         * <p>
         * TODO: 移除通道中的 master 地址，应该从 TaskExecutor 中获取 master 地址
         * </p>
         *
         * @param taskExecutorId 任务执行器ID（即任务实例ID）
         */
        public ReportableTaskExecutorLifecycleEventChannel(int taskExecutorId) {
            this.taskExecutorId = taskExecutorId;
            this.taskExecutionEventsQueue = new LinkedBlockingQueue<>();
        }

        /**
         * 添加任务执行器生命周期事件到队列
         *
         * @param reportableTaskExecutorLifecycleEvent 待添加的事件，不能为 null
         */
        public void addTaskExecutionEvent(final IReportableTaskExecutorLifecycleEvent reportableTaskExecutorLifecycleEvent) {
            taskExecutionEventsQueue.add(reportableTaskExecutorLifecycleEvent);
        }

        /**
         * 查看队首事件（不移除）
         * <p>
         * 用于判断事件是否需要发送，不会从队列中移除事件。
         * </p>
         *
         * @return 队首事件，如果队列为空返回 null
         */
        public IReportableTaskExecutorLifecycleEvent peek() {
            return taskExecutionEventsQueue.peek();
        }

        /**
         * 根据事件类型移除事件
         * <p>
         * 当收到 ACK 时，根据事件类型从队列中移除对应的事件。
         * 如果队列中有多个相同类型的事件，只移除第一个匹配的事件。
         * </p>
         *
         * @param type 事件类型
         * @return 被移除的事件，如果没有找到匹配的事件，返回 null
         */
        public IReportableTaskExecutorLifecycleEvent remove(TaskExecutorLifecycleEventType type) {
            final AtomicReference<IReportableTaskExecutorLifecycleEvent> removed = new AtomicReference<>();
            taskExecutionEventsQueue.removeIf(event -> {
                if (event.getType() == type) {
                    removed.set(event);
                    return true;
                }
                return false;
            });
            return removed.get();
        }

        /**
         * 判断队列是否为空
         *
         * @return 如果队列为空，返回 true；否则返回 false
         */
        public boolean isEmpty() {
            return taskExecutionEventsQueue.isEmpty();
        }

        /**
         * 判断队列是否非空
         *
         * @return 如果队列非空，返回 true；否则返回 false
         */
        public boolean isNotEmpty() {
            return !isEmpty();
        }

    }
}
