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

package org.apache.dolphinscheduler.server.master.engine.workflow.runnable;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.repository.CommandDao;
import org.apache.dolphinscheduler.dao.repository.WorkflowInstanceDao;
import org.apache.dolphinscheduler.server.master.engine.command.ICommandHandler;
import org.apache.dolphinscheduler.server.master.engine.exceptions.CommandDuplicateHandleException;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作流执行Runnable工厂类
 * <p>
 * 负责将Command转换为IWorkflowExecutionRunnable。
 * 这是Command到WorkflowExecutionRunnable转换的核心入口。
 * </p>
 * <p>
 * 核心职责：
 * <ol>
 *   <li>使用事务确保Command只被处理一次（防止集群环境下重复处理）</li>
 *   <li>根据Command的commandType选择合适的ICommandHandler</li>
 *   <li>通过ICommandHandler将Command转换为WorkflowExecutionRunnable</li>
 * </ol>
 * </p>
 * <p>
 * 工作流程：
 * <ol>
 *   <li>deleteCommandOrThrow: 在事务中删除Command，如果删除失败说明已被其他Master处理</li>
 *   <li>doCreateWorkflowExecutionRunnable: 根据commandType找到对应的ICommandHandler并处理</li>
 *   <li>ICommandHandler.handleCommand: 创建WorkflowExecuteContext和WorkflowExecutionRunnable</li>
 * </ol>
 * </p>
 * <p>
 * 涉及的CommandHandler类型：
 * <ul>
 *   <li>RunWorkflowCommandHandler: 处理START_PROCESS命令，启动新的工作流实例</li>
 *   <li>ReRunWorkflowCommandHandler: 处理REPEAT_RUNNING命令，重新运行工作流</li>
 *   <li>RecoverFailureTaskCommandHandler: 处理START_FAILURE_TASK_PROCESS命令，从失败任务恢复</li>
 *   <li>WorkflowFailoverCommandHandler: 处理故障转移场景</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class WorkflowExecutionRunnableFactory {

    /**
     * Command处理器列表（由Spring自动注入）
     * <p>每个ICommandHandler负责处理特定类型的Command</p>
     */
    @Autowired
    private List<ICommandHandler> commandHandlers;

    /**
     * 工作流实例DAO（已废弃，但保留用于兼容）
     */
    @Autowired
    private WorkflowInstanceDao workflowInstanceDao;

    /**
     * Command DAO，用于删除已处理的Command
     */
    @Autowired
    private CommandDao commandDao;

    /**
     * 从Command创建WorkflowExecutionRunnable
     * <p>
     * 使用事务确保Command只被处理一次。在Master集群环境下，如果多个Master同时读取到同一个Command，
     * 通过事务删除Command可以确保只有一个Master能够成功处理（通过删除操作的成功与否来判断）。
     * </p>
     * <p>
     * 如果Command已被其他Master处理（删除失败），会抛出CommandDuplicateHandleException异常。
     * </p>
     *
     * @param command 待处理的命令
     * @return 工作流执行Runnable实例
     * @throws CommandDuplicateHandleException 如果Command已被其他Master处理
     */
    @Transactional
    public IWorkflowExecutionRunnable createWorkflowExecuteRunnable(Command command) {
        deleteCommandOrThrow(command);
        return doCreateWorkflowExecutionRunnable(command);
    }

    /**
     * 实际创建工作流执行Runnable的方法
     * <p>
     * 根据Command的commandType，从commandHandlers列表中找到对应的ICommandHandler，
     * 然后调用其handleCommand方法将Command转换为WorkflowExecutionRunnable。
     * </p>
     * <p>
     * 每个WorkflowExecutionRunnable代表一个工作流实例的执行。
     * 根据命令类型的不同，可能会在数据库中创建工作流实例（如START_PROCESS），
     * 也可能复用已存在的工作流实例（如REPEAT_RUNNING）。
     * </p>
     *
     * @param command 待处理的命令
     * @return 工作流执行Runnable实例
     * @throws IllegalArgumentException 如果找不到对应的CommandHandler
     */
    private IWorkflowExecutionRunnable doCreateWorkflowExecutionRunnable(Command command) {
        final CommandType commandType = command.getCommandType();
        final ICommandHandler commandHandler = commandHandlers
                .stream()
                .filter(c -> c.commandType() == commandType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find ICommandHandler for commandType: " + commandType));
        return commandHandler.handleCommand(command);
    }

    /**
     * 从数据库中删除Command，如果Command不存在则抛出异常
     * <p>
     * 这个方法通过删除操作来确保Command只被处理一次：
     * <ul>
     *   <li>如果删除成功：说明这是第一次处理该Command，可以继续</li>
     *   <li>如果删除失败：说明Command已被其他Master处理，抛出异常防止重复处理</li>
     * </ul>
     * </p>
     * <p>
     * 这个机制在Master集群环境下非常重要，可以防止同一个Command被多个Master重复处理。
     * </p>
     *
     * @param command 待删除的命令
     * @throws CommandDuplicateHandleException 如果Command不存在（已被处理）
     */
    private void deleteCommandOrThrow(Command command) {
        boolean deleteResult = commandDao.deleteById(command.getId());
        if (!deleteResult) {
            throw new CommandDuplicateHandleException(command);
        }
    }
}
