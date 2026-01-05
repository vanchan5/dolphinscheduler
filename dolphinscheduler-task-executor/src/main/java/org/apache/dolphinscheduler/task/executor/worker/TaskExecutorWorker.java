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

package org.apache.dolphinscheduler.task.executor.worker;

import org.apache.dolphinscheduler.task.executor.ITaskExecutor;
import org.apache.dolphinscheduler.task.executor.log.TaskExecutorMDCUtils;
import org.apache.dolphinscheduler.task.executor.log.TaskExecutorMDCUtils.MDCAutoClosable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaskExecutorWorker extends AbstractTaskExecutorWorker {

    private final Map<Integer, ITaskExecutor> registeredTaskExecutors = new ConcurrentHashMap<>();

    private final Map<Integer, ITaskExecutor> activeTaskExecutors = new ConcurrentHashMap<>();

    private final Lock activeTaskExecutorsChangeLock = new ReentrantLock();

    private final Condition activeTaskExecutorEmptyCondition = activeTaskExecutorsChangeLock.newCondition();

    @Getter
    private final int workerId;

    public TaskExecutorWorker(int workerId) {
        this.workerId = workerId;
    }

    @Override
    public int getId() {
        return workerId;
    }

    @Override
    public void start() {
        while (true) {
            long minNextTrackDelay = 100;
            //
            for (final ITaskExecutor taskExecutor : activeTaskExecutors.values()) {
                // 设置MDC上下文：将任务执行器的logPath放入线程本地存储（MDC）
                // 在这个try-with-resources代码块内，所有日志都会自动写入到taskExecutor.getLogPath()对应的文件
                // 
                // 工作原理：
                // 1. TaskExecutorMDCUtils.logWithMDC(taskExecutor) 将 logPath 放入当前线程的 MDC
                // 2. 当代码块内调用 log.info()、log.error() 等日志方法时，Logback 会拦截日志事件
                // 3. TaskLogFilter 检查 MDC 中是否有 taskInstanceLogFullPath，如果有则接受该日志
                // 4. TaskLogDiscriminator 从 MDC 中获取 logPath 作为区分值
                // 5. SiftingAppender 根据区分值查找或创建对应的 FileAppender
                // 6. FileAppender 将日志写入到 logPath 对应的文件
                // 7. try-with-resources 结束时自动清理 MDC，避免影响其他任务

                // 步骤1: logWithMDC() 被调用
                //   → MDC.put("taskInstanceLogFullPath", taskExecutor.getLogPath())
                //   → MDC.put("taskInstanceId", taskExecutor.getId())
                try (final MDCAutoClosable closable = TaskExecutorMDCUtils.logWithMDC(taskExecutor)) {
                    try {
                        if (!taskExecutor.isStarted()) {
                            // 在这个代码块内，所有日志都会写入到taskExecutor.getLogPath()对应的文件
                            taskExecutor.start();// 步骤2: 任务开始执行
                            // 步骤3: 任务代码中调用 log.info("xxx")
                        }

                        final long remainingTrackDelay = taskExecutor.getRemainingTrackDelay();
                        if (remainingTrackDelay > 0) {
                            minNextTrackDelay = Math.min(minNextTrackDelay, remainingTrackDelay);
                        } else {
                            trackTaskExecutorState(taskExecutor);// 步骤4: 跟踪任务状态
                            // 步骤5: 可能还有其他日志输出
                        }// 步骤6: try-with-resources 自动关闭
                        //   → closable.close()
                        //   → MDC.remove("taskInstanceLogFullPath")
                        //   → MDC.remove("taskInstanceId")
                    } catch (Throwable e) {
                        log.error("{} execute failed", taskExecutor, e);
                        onTaskExecutorFailed(taskExecutor);
                    }
                }
            }

            activeTaskExecutorsChangeLock.lock();
            try {
                if (activeTaskExecutors.isEmpty()) {
                    activeTaskExecutorEmptyCondition.await();
                } else {
                    activeTaskExecutorEmptyCondition.await(minNextTrackDelay, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                log.info("TaskExecutorWorker(id={}) is interrupted", workerId, e);
                break;
            } finally {
                activeTaskExecutorsChangeLock.unlock();
            }
        }
    }

    @Override
    public void registerTaskExecutor(final ITaskExecutor taskExecutor) {
        final Integer taskExecutorId = taskExecutor.getId();
        if (registeredTaskExecutors.containsKey(taskExecutorId)) {
            throw new IllegalStateException(
                    "The TaskExecutorWorker has already registered " + taskExecutor);
        }
        registeredTaskExecutors.put(taskExecutorId, taskExecutor);
    }

    @Override
    public void unRegisterTaskExecutor(final ITaskExecutor taskExecutor) {
        final Integer taskExecutorId = taskExecutor.getId();
        if (!registeredTaskExecutors.containsKey(taskExecutorId)) {
            throw new IllegalStateException(
                    "The TaskExecutorWorker has not registered " + taskExecutor);
        }
        registeredTaskExecutors.remove(taskExecutorId);
    }

    @Override
    public void fireTaskExecutor(final ITaskExecutor taskExecutor) {
        activeTaskExecutorsChangeLock.lock();
        try {
            final Integer taskExecutorId = taskExecutor.getId();
            if (!registeredTaskExecutors.containsKey(taskExecutorId)) {
                throw new IllegalStateException(
                        "The TaskExecutorWorker has not registered " + taskExecutor);
            }
            if (activeTaskExecutors.containsKey(taskExecutorId)) {
                throw new IllegalStateException(
                        "The TaskExecutorWorker has already fired " + taskExecutor);
            }
            activeTaskExecutors.put(taskExecutorId, taskExecutor);
            activeTaskExecutorEmptyCondition.signalAll();
        } finally {
            activeTaskExecutorsChangeLock.unlock();
        }
    }

    @Override
    public void unFireTaskExecutor(ITaskExecutor taskExecutor) {
        try {
            activeTaskExecutorsChangeLock.lock();
            final Integer taskExecutorId = taskExecutor.getId();
            activeTaskExecutors.remove(taskExecutorId);
        } finally {
            activeTaskExecutorsChangeLock.unlock();
        }
    }

    @Override
    public int getRegisteredTaskExecutorSize() {
        return registeredTaskExecutors.size();
    }

    @Override
    public int getFiredTaskExecutorSize() {
        return activeTaskExecutors.size();
    }

}
