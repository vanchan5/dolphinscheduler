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

package org.apache.dolphinscheduler.server.master.engine.system;

import org.apache.dolphinscheduler.common.lifecycle.ServerLifeCycleManager;
import org.apache.dolphinscheduler.common.thread.BaseDaemonThread;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.server.master.engine.system.event.*;
import org.apache.dolphinscheduler.server.master.failover.FailoverCoordinator;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.time.StopWatch;

import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class SystemEventBusFireWorker extends BaseDaemonThread implements AutoCloseable {

    @Autowired
    private SystemEventBus systemEventBus;

    @Autowired
    private FailoverCoordinator failoverCoordinator;

    @Autowired
    private List<ISystemEventHandler> systemEventHandlers;

    private static boolean flag = false;

    public SystemEventBusFireWorker() {
        super("SystemEventBusFireWorker");
    }

    @Override
    public void start() {
        flag = true;
        super.start();
        log.info("SystemEventBusFireWorker started");
    }

    /**
     * 工作线程主循环方法
     * 持续从系统事件总线中取出事件并触发相应的处理器进行处理
     */
    @Override
    public void run() {
        // 持续运行直到标志位为false
        while (flag) {
            final AbstractSystemEvent systemEvent;
            try {
                /**
                 * 从事件总线中阻塞等待并取出一个系统事件
                 *   DelayQueue.take()工作原理：
                 *   1. 返回时间：调用事件对象的getDelay()方法获取剩余延迟时间
                 *      - 返回值 > 0：事件未到期，线程会阻塞等待直到到期
                 *      - 返回值 <= 0：事件已到期，立即返回该事件
                 *   2. 批量过期处理：DelayQueue内部使用PriorityQueue按过期时间排序
                 *      - 每次take()只返回队列头部最早到期的一个事件
                 *      - 如果有多个事件同时到期，它们会按过期时间顺序依次被取出
                 *      - 由于是单线程循环处理，批量到期的事件会按顺序依次处理，不会丢失
                 *   如果没有事件，线程会在此处阻塞等待
                 *
                 * a. take()出来的事件有{@link MasterFailoverEvent}和{@link WorkerFailoverEvent} 和 {@link GlobalMasterFailoverEvent}
                 * b. 系统事件处理根据事件类型匹配对应handler进行处理 {@link SystemEventBusFireWorker#fireSystemEvent(AbstractSystemEvent)}  -> 策略模式
                 *    一个事件有可以有多个handler
                 */
                systemEvent = systemEventBus.take();
            } catch (InterruptedException interruptedException) {
                // 如果线程被中断，恢复中断状态并退出循环
                Thread.currentThread().interrupt();
                log.warn("SystemEventBusFireWorker has been interrupted", interruptedException);
                break;
            }
            // 检查服务器生命周期状态，如果已停止则退出循环
            if (ServerLifeCycleManager.isStopped()) {
                log.info("SystemEventBusFireWorker has been stopped");
                break;
            }
            try {
                // 触发系统事件处理
                fireSystemEvent(systemEvent);
            } catch (Exception ex) {
                // 如果处理事件时发生异常，将事件重新放回事件总线
                // 这样可以确保事件不会丢失，稍后可以重试处理
                systemEventBus.publish(systemEvent);
                log.error("Fire SystemEvent: {} failed", systemEvent, ex);
                // 等待10秒后继续处理，避免异常事件导致循环过快
                ThreadUtils.sleep(10_000);
            }
        }
    }

    /**
     * 触发系统事件处理
     * 根据事件类型找到匹配的事件处理器并执行处理逻辑
     *
     * @param systemEvent 待处理的系统事件
     */
    private void fireSystemEvent(final AbstractSystemEvent systemEvent) {
        // 创建并启动计时器，用于统计事件处理耗时
        final StopWatch stopWatch = StopWatch.createStarted();
        // 从所有注册的事件处理器中筛选出与当前事件类型匹配的处理器
        final List<ISystemEventHandler> matchedSystemEventHandlers = systemEventHandlers
                .stream()
                .filter(systemEventHandler -> systemEventHandler.matchState() == systemEvent.getEventType())
                .collect(Collectors.toList());
        // 如果没有找到匹配的处理器，记录错误日志并返回
        if (CollectionUtils.isEmpty(matchedSystemEventHandlers)) {
            log.error("No matched SystemEventHandler for SystemEvent: {}", systemEvent);
            return;
        }
        // 遍历所有匹配的处理器，依次执行处理逻辑
        // 一个事件可以有多个处理器，它们都会被调用
        matchedSystemEventHandlers.forEach(systemEventHandler -> systemEventHandler.handle(systemEvent));
        // 停止计时器并记录事件处理耗时
        stopWatch.stop();
        log.info("Fire SystemEvent: {} cost: {} ms", systemEvent, stopWatch.getTime());
    }

    @Override
    public void close() {
        flag = false;
        log.info("SystemEventBusFireWorker closed");
    }
}
