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

package org.apache.dolphinscheduler.task.executor.log;

import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.task.executor.ITaskExecutor;

import org.slf4j.MDC;

public class TaskExecutorMDCUtils {

    private static final String TASK_INSTANCE_ID_MDC_KEY = "taskInstanceId";
    private static final String TASK_INSTANCE_LOG_FULL_PATH_MDC_KEY = "taskInstanceLogFullPath";

    public static MDCAutoClosable logWithMDC(final ITaskExecutor taskExecutor) {
        return logWithMDC(taskExecutor.getId(), taskExecutor.getTaskExecutionContext().getLogPath());
    }

    public static MDCAutoClosable logWithMDC(final int taskExecutorId) {
        return logWithMDC(taskExecutorId, null);
    }

    public static MDCAutoClosable logWithMDC(final int taskExecutorId, final String logPath) {

        if (logPath != null) {
            MDC.put(TASK_INSTANCE_LOG_FULL_PATH_MDC_KEY, logPath);
        }

        MDC.put(Constants.TASK_INSTANCE_ID_MDC_KEY, String.valueOf(taskExecutorId));

        // 返回一个 AutoCloseable 的 Lambda 表达式，用于在 try-with-resources 语句结束时自动清理 MDC
        //
        // 为什么说是 Lambda 表达式？
        //   1. 方法返回类型是 MDCAutoClosable（第38行方法签名）
        //   2. MDCAutoClosable 接口定义（第91-95行）：
        //      public interface MDCAutoClosable extends AutoCloseable {
        //          void close();
        //      }
        //   3. MDCAutoClosable 是函数式接口（只有一个抽象方法 close()）
        //   4. return () -> { ... } 是 Lambda 表达式语法
        //   5. 这个 Lambda 表达式实现了 MDCAutoClosable 接口的 close() 方法
        //   6. 因为 MDCAutoClosable extends AutoCloseable，所以也实现了 AutoCloseable 接口
        //
        // 重要：为什么可以用 Lambda 表达式？
        //   - Lambda 表达式只能用于函数式接口（Functional Interface）
        //   - 函数式接口的定义：只有一个抽象方法的接口
        //   - MDCAutoClosable 只有一个抽象方法 close()，所以是函数式接口
        //   - 如果接口有多个抽象方法，就不能用 Lambda 表达式，因为无法确定要实现哪个方法
        //
        // 如果 MDCAutoClosable 有多个无参方法会怎样？
        //   假设接口定义：
        //     public interface MDCAutoClosable extends AutoCloseable {
        //         void close();
        //         void cleanup();  // 新增方法
        //     }
        //   此时：
        //     - 接口有两个抽象方法，不是函数式接口
        //     - return () -> { ... } 会编译错误：无法确定要实现 close() 还是 cleanup()
        //     - 必须使用匿名内部类明确指定要实现哪个方法
        //
        // 等价代码（不使用 Lambda）:
        //   return new MDCAutoClosable() {
        //       @Override
        //       public void close() {
        //           MDC.remove(TASK_INSTANCE_LOG_FULL_PATH_MDC_KEY);
        //           MDC.remove(Constants.TASK_INSTANCE_ID_MDC_KEY);
        //       }
        //   };
        //
        // 工作原理:
        //   这个 Lambda 表达式实现了 MDCAutoClosable 接口（继承自 AutoCloseable），
        //   当 try-with-resources 语句块结束时，会自动调用 close() 方法，从而执行 Lambda 体内的清理逻辑。
        //
        // 为什么需要清理 MDC:
        //   - 避免内存泄漏: MDC 使用 ThreadLocal 存储，如果不清理，可能导致内存泄漏
        //   - 避免日志混乱: 线程池复用线程时，如果不清理 MDC，后续任务可能使用前一个任务的 logPath
        //   - 确保线程安全: 每个任务执行完成后，必须清理自己的 MDC 上下文
        //
        // 使用示例:
        //   try (final MDCAutoClosable closable = TaskExecutorMDCUtils.logWithMDC(taskExecutor)) {
        //       // 在这个代码块内，MDC 中已设置 logPath
        //       // 所有日志都会写入到 taskExecutor.getLogPath() 对应的文件
        //       taskExecutor.start();
        //       trackTaskExecutorState(taskExecutor);
        //   } // 自动调用 closable.close()
        //     // → MDC.remove("taskInstanceLogFullPath")
        //     // → MDC.remove("taskInstanceId")
        //     // MDC 已清理，后续日志不会写入任务日志文件
        //
        // Lambda 表达式执行时机:
        //   1. 设置阶段: 调用 logWithMDC() 时，立即将 logPath 和 taskInstanceId 放入 MDC
        //   2. 使用阶段: try 块内的代码执行，所有日志都使用 MDC 中的 logPath
        //   3. 清理阶段: try 块结束时（正常结束或异常），自动调用 Lambda 表达式，清理 MDC
        //
        // 线程安全说明:
        //   - 线程本地存储: MDC 使用 ThreadLocal 实现，每个线程有独立的 MDC 副本
        //   - 线程隔离: 不同线程的 MDC 互不影响，多任务并发执行时不会混淆
        //   - 自动清理: 即使发生异常，try-with-resources 也会确保清理 MDC
        //
        // 潜在问题和解决方案:
        //   - 问题: 如果忘记使用 try-with-resources，MDC 不会被清理，可能导致日志混乱
        //   - 解决方案: 强制使用 try-with-resources，编译时检查，避免遗漏
        //   - 问题: 线程池复用线程时，如果前一个任务的 MDC 未清理，会影响后续任务
        //   - 解决方案: 使用 try-with-resources 确保每次任务执行后都清理 MDC
        return () -> {
            // 清理 taskInstanceLogFullPath：移除后，TaskLogFilter 会拒绝后续日志写入任务日志文件
            MDC.remove(TASK_INSTANCE_LOG_FULL_PATH_MDC_KEY);
            // 清理 taskInstanceId：移除后，日志中不再包含任务实例ID信息
            MDC.remove(Constants.TASK_INSTANCE_ID_MDC_KEY);
        };
    }

    public interface MDCAutoClosable extends AutoCloseable {

        @Override
        void close();
    }

}
