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

package org.apache.dolphinscheduler.server.worker.executor;

import org.apache.dolphinscheduler.task.executor.eventbus.TaskExecutorLifecycleEventRemoteReporter;

import org.springframework.stereotype.Component;

/**
 * 物理任务执行器生命周期事件报告器
 * <p>
 * 这是 Worker 端负责将物理任务执行器的生命周期事件异步报告给 Master 的核心组件。
 * 它继承自 {@link TaskExecutorLifecycleEventRemoteReporter}，实现了事件收集、缓存、异步上报、重试机制和 ACK 处理等功能。
 * </p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>事件收集</b>: 接收来自 {@link PhysicalTaskExecutorLifecycleEventListener} 的任务执行器生命周期事件</li>
 *   <li><b>事件缓存</b>: 将事件按 {@code taskInstanceId} 分组存储到 {@code eventChannels} 中</li>
 *   <li><b>异步上报</b>: 通过守护线程定期轮询并发送事件到 Master</li>
 *   <li><b>重试机制</b>: 对于未收到 ACK 的事件，按照重试间隔（默认3分钟）重新发送</li>
 *   <li><b>ACK 处理</b>: 接收 Master 的 ACK，从缓存中移除已确认的事件</li>
 * </ul>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li><b>初始化</b>: Spring 容器创建实例时，通过构造函数注入 {@link PhysicalTaskExecutorEventRemoteReporterClient}</li>
 *   <li><b>启动</b>: 调用 {@link #start()} 方法启动守护线程，开始轮询处理事件</li>
 *   <li><b>事件收集</b>: 监听器调用 {@link #reportTaskExecutorLifecycleEvent(IReportableTaskExecutorLifecycleEvent)} 添加事件到通道</li>
 *   <li><b>事件处理</b>: 守护线程定期轮询所有通道，处理需要发送的事件</li>
 *   <li><b>事件发送</b>: 通过 {@link PhysicalTaskExecutorEventRemoteReporterClient} 发送事件到 Master</li>
 *   <li><b>ACK 处理</b>: 收到 Master 的 ACK 后，从通道中移除对应的事件</li>
 * </ol>
 *
 * <h3>事件类型</h3>
 * <p>
 * 支持以下生命周期事件的报告：
 * </p>
 * <ul>
 *   <li>{@code DISPATCHED}: 任务已分发到容器</li>
 *   <li>{@code RUNNING}: 任务开始执行</li>
 *   <li>{@code RUNTIME_CONTEXT_CHANGE}: 任务运行时上下文变更</li>
 *   <li>{@code PAUSED}: 任务已暂停</li>
 *   <li>{@code KILLED}: 任务已被杀死</li>
 *   <li>{@code SUCCESS}: 任务执行成功</li>
 *   <li>{@code FAILED}: 任务执行失败</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <ul>
 *   <li><b>异步非阻塞</b>: 事件收集和发送都是异步的，不阻塞任务执行器的正常执行</li>
 *   <li><b>线程安全</b>: 使用 {@code ConcurrentHashMap} 和 {@code ReentrantLock} 保证并发安全</li>
 *   <li><b>自动重试</b>: 未收到 ACK 的事件会自动重试，默认重试间隔为 3 分钟</li>
 *   <li><b>资源管理</b>: 收到 ACK 后自动清理事件，空通道自动移除</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // Spring 容器自动创建和注入
 * @Autowired
 * private PhysicalTaskExecutorLifecycleEventReporter eventReporter;
 *
 * // 在 PhysicalTaskEngineDelegator.start() 中启动
 * eventReporter.start();
 *
 * // 监听器会自动调用 reportTaskExecutorLifecycleEvent() 报告事件
 * }</pre>
 *
 * @see TaskExecutorLifecycleEventRemoteReporter
 * @see PhysicalTaskExecutorEventRemoteReporterClient
 * @see PhysicalTaskExecutorLifecycleEventListener
 * @see PhysicalTaskEngineDelegator
 * @since 3.0.0
 */
@Component
public class PhysicalTaskExecutorLifecycleEventReporter extends TaskExecutorLifecycleEventRemoteReporter {

    /**
     * 构造函数
     * <p>
     * 通过 Spring 的依赖注入机制创建实例，并调用父类构造函数初始化事件报告器。
     * </p>
     *
     * @param physicalTaskExecutorEventRemoteReporterClient RPC 客户端，用于与 Master 通信发送事件
     *                                                       不能为 null
     * @see TaskExecutorLifecycleEventRemoteReporter#TaskExecutorLifecycleEventRemoteReporter(String, ITaskExecutorEventRemoteReporterClient)
     */
    public PhysicalTaskExecutorLifecycleEventReporter(
                                                      final PhysicalTaskExecutorEventRemoteReporterClient physicalTaskExecutorEventRemoteReporterClient) {
        super("PhysicalTaskExecutorLifecycleEventReporter", physicalTaskExecutorEventRemoteReporterClient);
    }
}
