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

package org.apache.dolphinscheduler.common.model;

import org.apache.dolphinscheduler.common.thread.BaseDaemonThread;

import lombok.extern.slf4j.Slf4j;

/**
 * 心跳任务抽象基类，使用模板方法模式。
 *
 * <p>模板方法：{@link #run()} 定义算法骨架
 * <p>抽象方法：{@link #getHeartBeat()} 和 {@link BaseHeartBeatTask#writeHeartBeat(HeartBeat)} 由子类实现
 *
 * @param <T> 心跳数据类型，必须实现 {@link HeartBeat} 接口
 */
@Slf4j
public abstract class BaseHeartBeatTask<T extends HeartBeat> extends BaseDaemonThread {

    private static final long DEFAULT_HEARTBEAT_SCAN_INTERVAL = 1_000L;

    private final String threadName;
    private final long heartBeatInterval;

    protected boolean runningFlag;

    protected long lastWriteTime = 0L;

    protected T lastHeartBeat = null;

    public BaseHeartBeatTask(String threadName, long heartBeatInterval) {
        super(threadName);
        this.threadName = threadName;
        this.heartBeatInterval = heartBeatInterval;
        this.runningFlag = true;
    }

    @Override
    public synchronized void start() {
        log.info("Starting {}...", threadName);
        super.start();
        log.info("Started {}, heartBeatInterval: {}...", threadName, heartBeatInterval);
    }

    @Override
    public void run() {
        /**
         * 1. 为什么不会导致内存溢出和CPU飙高？
         *    a. CPU方面：
         *       - Thread.sleep(1000) 让线程每次循环休眠 1 秒，避免 while 循环空转占用 CPU
         *       - 如果没有 sleep，while 循环会空转，导致 CPU 使用率接近 100%（单核）
         *    b. 内存方面：
         *       - 每次循环都会调用 getHeartBeat() 创建新的 heartBeat 对象（局部变量）
         *       - 如果满足写入条件：heartBeat 赋值给 lastHeartBeat（成员变量），替换旧引用
         *       - 如果不满足条件：heartBeat 是局部变量，循环结束后变成垃圾对象，可被 GC 回收
         *       - 最多只有一个 heartBeat 对象存活（lastHeartBeat 指向的），不会有对象积累
         *       - Thread.sleep 降低了对象创建频率，给 GC 充分的回收时间
         *       - 如果没有 sleep，对象创建过快可能导致频繁 GC，GC 本身消耗 CPU，可能影响性能,增加 GC 压力（但通常不会直接 OOM）
         * 
         * 2. 为什么 runningFlag=false 时线程会退出？
         *    a. while (runningFlag) 是条件循环，当 runningFlag 为 false 时循环条件不满足，循环退出
         *    b. 循环退出后，run() 方法执行完毕，线程结束
         *    c. shutdown() 方法会在服务器关闭时被调用（如 MasterRegistryClient.close()），将 runningFlag 设置为 false
         *
         * 心跳线程模式：通过 sleep 控制 CPU 使用和对象创建频率，通过标志位实现优雅退出
         *
         * 定期写入：距离上次写入时间 >= heartBeatInterval（默认 10 秒）
         * 状态变化时立即写入：serverStatus 发生变化时立即写入
         */
        while (runningFlag) {
            try {
                T heartBeat = getHeartBeat();
                // if first time or heartBeat status changed, write heartBeatInfo into registry
                if (System.currentTimeMillis() - lastWriteTime >= heartBeatInterval
                        || !lastHeartBeat.getServerStatus().equals(heartBeat.getServerStatus())) {
                    lastHeartBeat = heartBeat;
                    writeHeartBeat(heartBeat);
                    lastWriteTime = System.currentTimeMillis();
                }
            } catch (Exception ex) {
                log.error("{} task execute failed", threadName, ex);
            } finally {
                try {
                    Thread.sleep(DEFAULT_HEARTBEAT_SCAN_INTERVAL);
                } catch (InterruptedException e) {
                    handleInterruptException(e);
                }
            }
        }
    }

    public void shutdown() {
        runningFlag = false;
        log.warn("{} finished...", threadName);
    }

    private void handleInterruptException(InterruptedException ex) {
        log.warn("{} has been interrupted", threadName, ex);
        Thread.currentThread().interrupt();
    }

    public abstract T getHeartBeat();

    public abstract void writeHeartBeat(T heartBeat);
}
