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

package org.apache.dolphinscheduler.server.master.engine.command;

import static java.util.concurrent.CompletableFuture.supplyAsync;

import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.common.enums.WorkflowExecutionStatus;
import org.apache.dolphinscheduler.common.thread.BaseDaemonThread;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.meter.metrics.MetricsProvider;
import org.apache.dolphinscheduler.meter.metrics.SystemMetrics;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.config.MasterServerLoadProtection;
import org.apache.dolphinscheduler.server.master.engine.IWorkflowRepository;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBusCoordinator;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBusFireWorker;
import org.apache.dolphinscheduler.server.master.engine.command.handler.AbstractCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.command.handler.RunWorkflowCommandHandler;
import org.apache.dolphinscheduler.server.master.engine.exceptions.CommandDuplicateHandleException;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.AbstractTaskLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler.AbstractTaskLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.AbstractWorkflowLifecycleLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.event.WorkflowStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler.AbstractWorkflowLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.lifecycle.handler.WorkflowStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.WorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.WorkflowExecutionRunnableFactory;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.IWorkflowStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.trigger.AbstractWorkflowTrigger;
import org.apache.dolphinscheduler.server.master.metrics.MasterServerMetrics;
import org.apache.dolphinscheduler.server.master.runner.WorkflowExecuteContext;
import org.apache.dolphinscheduler.service.command.CommandService;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Master scheduler thread, this thread will consume the commands from database and trigger processInstance executed.
 */
@Service
@Slf4j
public class CommandEngine extends BaseDaemonThread implements AutoCloseable {

    @Autowired
    private ICommandFetcher commandFetcher;

    @Autowired
    private CommandService commandService;

    @Autowired
    private MasterConfig masterConfig;

    @Autowired
    private IWorkflowRepository workflowRepository;

    @Autowired
    private WorkflowExecutionRunnableFactory workflowExecutionRunnableFactory;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private WorkflowEventBusCoordinator workflowEventBusCoordinator;

    private ExecutorService commandHandleThreadPool;

    private boolean flag = false;

    protected CommandEngine() {
        super("MasterCommandLoopThread");
    }

    @Override
    public synchronized void start() {
        log.info("MasterSchedulerBootstrap starting..");
        this.commandHandleThreadPool = ThreadUtils.newDaemonFixedThreadExecutor("MasterCommandHandleThreadPool",
                Runtime.getRuntime().availableProcessors());
        flag = true;
        super.start();
        log.info("MasterSchedulerBootstrap started...");
    }

    @Override
    public void close() throws Exception {
        log.info("MasterSchedulerBootstrap stopping...");
        flag = false;
        log.info("MasterSchedulerBootstrap stopped...");
    }

    @Override
    public void run() {
        MasterServerLoadProtection serverLoadProtection = masterConfig.getServerLoadProtection();
        while (flag) {
            try {
                // todo: if the workflow event queue is much, we need to handle the back pressure
                SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
                if (serverLoadProtection.isOverload(systemMetrics)) {
                    log.warn("The current server is overload, cannot consumes commands.");
                    MasterServerMetrics.incMasterOverload();
                    Thread.sleep(Constants.SLEEP_TIME_MILLIS);
                    continue;
                }
                /**
                 * command初始状态：
                 *      {@link CommandType.START_PROCESS} -> {@link RunWorkflowCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
                 *      {@link CommandType.SCHEDULER} 等等
                 * workflowInstance初始状态：
                 *      {@link WorkflowExecutionStatus.SUBMITTED_SUCCESS} ->
                 *      handler构建完后 {@link RunWorkflowCommandHandler#assembleWorkflowInstance(WorkflowExecuteContext.WorkflowExecuteContextBuilder)}
                 *      更新状态为 {@link WorkflowExecutionStatus.RUNNING_EXECUTION}
                 *
                 * command和workflowInstance来源：{@link AbstractWorkflowTrigger#constructTriggerCommand(Object, WorkflowInstance)}
                 *
                 * 基于 SLOT 的分配机制在 Command ID 连续且分布均匀时基本均衡
                 */
                List<Command> commands = commandFetcher.fetchCommands();
                if (CollectionUtils.isEmpty(commands)) {
                    // indicate that no command ,sleep for 1s
                    Thread.sleep(Constants.SLEEP_TIME_MILLIS);
                    continue;
                }

                // ============================================================
                // 并行处理多个命令（无依赖关系场景）
                // ============================================================
                // 设计思路：
                // 1. 多个命令之间无依赖关系，可以全部并行异步执行，提高吞吐量
                // 2. 每个命令的处理流程：创建工作流执行器 -> 启动工作流 -> 记录成功日志
                // 3. 使用 CompletableFuture 链式调用串联处理步骤，代码清晰易维护
                // 4. 使用 exceptionally 统一处理异常，单个命令失败不影响其他命令
                // 5. 使用 allOf().join() 等待所有命令处理完成后再继续下一轮循环
                //
                // 性能优势：
                // - 串行处理：总耗时 = 命令1耗时 + 命令2耗时 + ... + 命令N耗时
                // - 并行处理：总耗时 = max(命令1耗时, 命令2耗时, ..., 命令N耗时)
                // ============================================================
                List<CompletableFuture<Void>> allCompleteFutures = new ArrayList<>();
                for (Command command : commands) {
                    // 为每个命令创建异步处理链：
                    // 步骤1: bootstrapCommand(command) - 在 commandHandleThreadPool 中异步创建工作流执行器
                    // 步骤2: thenAccept(bootstrapWorkflowExecutionRunnable) - 启动工作流执行（依赖步骤1的结果）
                    // 步骤3: thenAccept(bootstrapSuccess) - 成功后记录日志（依赖步骤2的结果）
                    // 异常处理: exceptionally - 统一处理异常，避免单个命令失败影响其他命令
                    CompletableFuture<Void> completableFuture = bootstrapCommand(command)
                            .thenAccept(this::bootstrapWorkflowExecutionRunnable)
                            .thenAccept((unused) -> bootstrapSuccess(command))
                            .exceptionally(throwable -> bootstrapError(command, throwable));
                    allCompleteFutures.add(completableFuture);
                }
                // 等待所有命令处理完成后再继续下一轮循环
                // 使用 allOf() 等待所有 CompletableFuture 完成，join() 阻塞等待结果
                // 此时所有命令都已完成，join() 会立即返回，不会再次阻塞
                CompletableFuture.allOf(allCompleteFutures.toArray(new CompletableFuture[0])).join();
            } catch (InterruptedException interruptedException) {
                log.warn("Master schedule bootstrap interrupted, close the loop", interruptedException);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Master schedule workflow error", e);
                // sleep for 1s here to avoid the database down cause the exception boom
                ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
            }
        }
    }

    /**
     * 先删除数据库的Command,增加Command的设计是为了分布式的并发处理
     *
     * @param command
     * @return
     */
    private CompletableFuture<IWorkflowExecutionRunnable> bootstrapCommand(Command command) {
        return supplyAsync(
                () -> workflowExecutionRunnableFactory.createWorkflowExecuteRunnable(command), commandHandleThreadPool);
    }

    private CompletableFuture<Void> bootstrapWorkflowExecutionRunnable(IWorkflowExecutionRunnable workflowExecutionRunnable) {
        final WorkflowInstance workflowInstance =
                workflowExecutionRunnable.getWorkflowExecuteContext().getWorkflowInstance();
        if (workflowInstance.getState() == WorkflowExecutionStatus.SERIAL_WAIT) {
            log.info("The workflow {} state is: {} will not be trigger now",
                    workflowInstance.getName(),
                    workflowInstance.getState());
            return CompletableFuture.completedFuture(null);
        }

        workflowRepository.put(workflowExecutionRunnable);
        workflowEventBusCoordinator.registerWorkflowEventBus(workflowExecutionRunnable);
        /**
         * 组装事件总线:
         * {@link WorkflowExecutionRunnableFactory#createWorkflowExecuteRunnable(Command)} ->
         * {@link AbstractCommandHandler#handleCommand(Command)} ->
         * 事件入队 -》 事件订阅:
         * {@link WorkflowEventBusFireWorker#fireAllRegisteredEvent()} -> workflowEventBus.poll()
         *
         * 发布WorkflowStartLifecycleEvent,触发{@link WorkflowStartLifecycleEventHandler}处理器
         *
         * 其他{@link AbstractWorkflowLifecycleLifecycleEvent} 在其他地方触发,比如
         * 暂停 {@link WorkflowExecutionRunnable#pause()}
         *
         * 触发处理流程
         * {@link AbstractWorkflowLifecycleLifecycleEvent} ->
         * {@link AbstractWorkflowLifecycleEventHandler} ->
         * {@link IWorkflowStateAction} ->
         * {@link AbstractTaskLifecycleEvent} ->
         * {@link AbstractTaskLifecycleEventHandler}
         *
         */
        workflowExecutionRunnable.getWorkflowEventBus()
                .publish(WorkflowStartLifecycleEvent.of(workflowExecutionRunnable));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> bootstrapSuccess(Command command) {
        log.info("Success bootstrap command {}", JSONUtils.toPrettyJsonString(command));
        MasterServerMetrics.incMasterConsumeCommand(1);
        return CompletableFuture.completedFuture(null);
    }

    private Void bootstrapError(Command command, Throwable throwable) {
        if (throwable instanceof CommandDuplicateHandleException) {
            log.warn("Handle command failed, the command: {} has been handled by other master",
                    command,
                    throwable);
            return null;
        }
        log.error("Failed bootstrap command {} ", JSONUtils.toPrettyJsonString(command), throwable);
        commandService.moveToErrorCommand(command, ExceptionUtils.getStackTrace(throwable));
        return null;
    }

}
