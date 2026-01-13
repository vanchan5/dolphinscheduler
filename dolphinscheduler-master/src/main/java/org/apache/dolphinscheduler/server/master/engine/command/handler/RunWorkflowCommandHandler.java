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

package org.apache.dolphinscheduler.server.master.engine.command.handler;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.common.enums.WorkflowExecutionStatus;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.dao.repository.TaskInstanceDao;
import org.apache.dolphinscheduler.dao.repository.WorkflowInstanceDao;
import org.apache.dolphinscheduler.extract.master.command.ICommandParam;
import org.apache.dolphinscheduler.extract.master.command.RunWorkflowCommandParam;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowManualTriggerRequest;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.engine.graph.IWorkflowGraph;
import org.apache.dolphinscheduler.server.master.engine.graph.WorkflowExecutionGraph;
import org.apache.dolphinscheduler.server.master.engine.graph.WorkflowGraphTopologyLogicalVisitor;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.event.TaskStartLifecycleEvent;
import org.apache.dolphinscheduler.server.master.engine.task.lifecycle.handler.TaskStartLifecycleEventHandler;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.ITaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.TaskExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.task.runnable.TaskExecutionRunnableBuilder;
import org.apache.dolphinscheduler.server.master.engine.workflow.runnable.IWorkflowExecutionRunnable;
import org.apache.dolphinscheduler.server.master.engine.workflow.statemachine.WorkflowRunningStateAction;
import org.apache.dolphinscheduler.server.master.engine.workflow.trigger.WorkflowManualTrigger;
import org.apache.dolphinscheduler.server.master.runner.WorkflowExecuteContext.WorkflowExecuteContextBuilder;
import org.apache.dolphinscheduler.service.expand.CuringParamsService;

import org.apache.commons.collections4.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 启动工作流命令处理器
 * <p>
 * RunWorkflowCommandHandler用于处理{@link CommandType#START_PROCESS}类型的命令，负责启动新的工作流实例。
 * 这是工作流执行的核心入口，负责将工作流定义转换为可执行的工作流实例。
 * </p>
 * <p>
 * 核心职责：
 * <ul>
 *   <li>组装工作流实例（assembleWorkflowInstance）：从数据库查询工作流实例，更新状态为RUNNING_EXECUTION</li>
 *   <li>组装工作流执行图（assembleWorkflowExecutionGraph）：构建WorkflowExecutionGraph和所有TaskExecutionRunnable</li>
 *   <li>参数合并（mergeCommandParamsWithWorkflowParams）：合并命令参数和工作流全局参数</li>
 * </ul>
 * </p>
 * <p>
 * 与容错恢复的区别：
 * <ul>
 *   <li>RunWorkflowCommandHandler：启动新工作流，taskInstance为null，在首次触发时创建</li>
 *   <li>WorkflowFailoverCommandHandler：容错恢复，从已有TaskInstance恢复，taskInstance不为null</li>
 * </ul>
 * </p>
 * <p>
 * 任务实例创建时机：
 * <ul>
 *   <li>在assembleWorkflowExecutionGraph中，所有TaskExecutionRunnable的taskInstance初始为null</li>
 *   <li>在任务首次触发时，通过{@link TaskStartLifecycleEventHandler#handle}调用</li>
 *   <li>{@link TaskExecutionRunnable#initializeFirstRunTaskInstance()}创建TaskInstance</li>
 * </ul>
 * </p>
 * <p>
 * 可以通过{@link RunWorkflowCommandParam}指定起始节点，如果未指定则从所有起始节点开始执行。
 * </p>
 *
 * @see AbstractCommandHandler
 * @see WorkflowFailoverCommandHandler
 * @see TaskStartLifecycleEventHandler
 * @see TaskExecutionRunnable#initializeFirstRunTaskInstance()
 */
@Component
public class RunWorkflowCommandHandler extends AbstractCommandHandler {

    /**
     * 工作流实例数据访问对象
     * 用于查询和更新工作流实例信息
     */
    @Autowired
    private WorkflowInstanceDao workflowInstanceDao;

    /**
     * 任务实例数据访问对象
     * 用于查询任务实例信息（在容错恢复场景中使用）
     */
    @Autowired
    private TaskInstanceDao taskInstanceDao;

    /**
     * Master节点配置
     * 用于获取Master节点地址，设置到工作流实例的host字段
     */
    @Autowired
    private MasterConfig masterConfig;

    /**
     * Spring应用上下文
     * 用于获取各种Bean（如TaskInstanceFactories、TaskExecutionContextFactory等）
     * 传递给TaskExecutionRunnableBuilder，最终传递给TaskExecutionRunnable
     */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 参数处理服务
     * 用于处理工作流参数和命令参数的合并
     */
    @Autowired
    private CuringParamsService curingParamsService;

    /**
     * 组装工作流实例
     * <p>
     * 从数据库查询工作流实例，更新其状态和相关信息，然后设置到WorkflowExecuteContextBuilder中。
     * 注意：工作流实例在调用此方法之前已经通过{@link WorkflowManualTrigger#constructWorkflowInstance}创建并插入数据库。
     * </p>
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>从数据库查询工作流实例（根据command中的workflowInstanceId）</li>
     *   <li>更新工作流实例状态为RUNNING_EXECUTION（运行中）</li>
     *   <li>设置执行主机地址（Master节点的IP地址）</li>
     *   <li>设置命令参数（从Command中获取）</li>
     *   <li>合并并设置全局参数（合并Command参数和工作流定义参数）</li>
     *   <li>更新数据库中的工作流实例</li>
     *   <li>设置到WorkflowExecuteContextBuilder中</li>
     * </ol>
     * </p>
     * <p>
     * 参数合并规则：
     * <ul>
     *   <li>如果Command参数和工作流定义参数有重复的key，Command参数会覆盖工作流定义参数</li>
     *   <li>最终参数通过{@link #mergeCommandParamsWithWorkflowParams}方法合并</li>
     * </ul>
     * </p>
     *
     * @param workflowExecuteContextBuilder 工作流执行上下文构建器
     * @see WorkflowManualTrigger#constructWorkflowInstance(WorkflowManualTriggerRequest)
     * @see #mergeCommandParamsWithWorkflowParams(Command, WorkflowDefinition)
     */
    @Override
    protected void assembleWorkflowInstance(final WorkflowExecuteContextBuilder workflowExecuteContextBuilder) {
        final WorkflowDefinition workflowDefinition = workflowExecuteContextBuilder.getWorkflowDefinition();
        final Command command = workflowExecuteContextBuilder.getCommand();
        
        // 从数据库查询工作流实例（工作流实例在之前已经创建并插入数据库）
        final WorkflowInstance workflowInstance = workflowInstanceDao.queryById(command.getWorkflowInstanceId());
        
        // 更新工作流实例状态为运行中，并记录状态变化原因（命令类型）
        workflowInstance.setStateWithDesc(WorkflowExecutionStatus.RUNNING_EXECUTION, command.getCommandType().name());
        
        // 设置执行主机地址（当前Master节点的IP地址）
        workflowInstance.setHost(masterConfig.getMasterAddress());
        
        // 设置命令参数（JSON格式，包含执行工作流所需的各种参数）
        workflowInstance.setCommandParam(command.getCommandParam());
        
        // 合并命令参数和工作流定义参数，设置全局参数
        // 如果参数有重复，命令参数会覆盖工作流定义参数
        workflowInstance.setGlobalParams(mergeCommandParamsWithWorkflowParams(command, workflowDefinition));
        
        // 更新数据库中的工作流实例
        workflowInstanceDao.upsertWorkflowInstance(workflowInstance);
        
        // 设置到WorkflowExecuteContextBuilder中，供后续步骤使用
        workflowExecuteContextBuilder.setWorkflowInstance(workflowInstance);
    }

    /**
     * 组装工作流执行图
     * <p>
     * 这是工作流执行的核心方法，负责构建WorkflowExecutionGraph和所有TaskExecutionRunnable。
     * 该方法将静态的WorkflowGraph转换为动态的WorkflowExecutionGraph，为每个任务创建可执行的TaskExecutionRunnable。
     * </p>
     * <p>
     * 重要特性：
     * <ul>
     *   <li><b>延迟创建TaskInstance</b>：在RunWorkflowCommandHandler中，所有TaskExecutionRunnable的taskInstance初始为null。
     *       任务实例在首次触发时通过{@link TaskStartLifecycleEventHandler#handle}调用
     *       {@link TaskExecutionRunnable#initializeFirstRunTaskInstance()}创建。
     *       这样可以避免不必要的数据库写入，只在真正需要执行时才创建任务实例。</li>
     *   <li><b>与容错恢复的区别</b>：WorkflowFailoverCommandHandler会从已有TaskInstance恢复，taskInstance不为null。</li>
     *   <li><b>循环引用设计</b>：所有TaskExecutionRunnable都引用同一个WorkflowExecutionGraph实例，
     *       这样每个任务都可以访问整个执行图，用于依赖检查和任务触发。</li>
     * </ul>
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>创建空的WorkflowExecutionGraph实例</li>
     *   <li>定义taskExecutionRunnableCreator函数（BiConsumer），用于处理每个遍历到的节点</li>
     *   <li>创建WorkflowGraphTopologyLogicalVisitor，配置：
     *       <ul>
     *         <li>taskDependType：任务依赖类型（从工作流实例获取）</li>
     *         <li>workflowGraph：静态工作流图</li>
     *         <li>startNodes：起始节点列表（从command_param解析，如果为空则使用所有起始节点）</li>
     *         <li>visitFunction：taskExecutionRunnableCreator函数</li>
     *       </ul>
     *   </li>
     *   <li>调用visit()方法进行拓扑遍历</li>
     *   <li>在遍历过程中，对每个节点：
     *       <ul>
     *         <li>创建TaskExecutionRunnableBuilder，设置所有必要字段</li>
     *         <li>创建TaskExecutionRunnable实例（taskInstance为null）</li>
     *         <li>调用workflowExecutionGraph.addNode()添加到执行图</li>
     *         <li>调用workflowExecutionGraph.addEdge()添加依赖关系</li>
     *       </ul>
     *   </li>
     *   <li>将构建好的WorkflowExecutionGraph设置到WorkflowExecuteContextBuilder中</li>
     * </ol>
     * </p>
     * <p>
     * 拓扑遍历说明：
     * <ul>
     *   <li>根据taskDependType选择遍历策略：
     *       <ul>
     *         <li>TASK_ONLY(0)：只遍历起始节点</li>
     *         <li>TASK_PRE(1)：遍历起始节点及其前置节点</li>
     *         <li>TASK_POST(2)：遍历起始节点及其后续节点（默认）</li>
     *       </ul>
     *   </li>
     *   <li>使用拓扑排序确保按依赖顺序创建TaskExecutionRunnable</li>
     *   <li>通过inDegreeMap跟踪每个节点的入度，只有入度为0的节点才会被处理</li>
     * </ul>
     * </p>
     * <p>
     * 后续执行流程：
     * <ul>
     *   <li>构建完成后，WorkflowExecutionRunnable会发布WorkflowStartLifecycleEvent</li>
     *   <li>WorkflowStartLifecycleEventHandler处理事件，调用WorkflowRunningStateAction</li>
     *   <li>WorkflowRunningStateAction会遍历所有起始节点，为每个节点发布TaskStartLifecycleEvent</li>
     *   <li>TaskStartLifecycleEventHandler处理事件，调用TaskExecutionRunnable.initializeFirstRunTaskInstance()创建TaskInstance</li>
     * </ul>
     * </p>
     *
     * <ul>
     *   <li> RunWorkflowCommandHandler（START_PROCESS）：启动新的工作流，任务实例尚未创建，此时设置 taskInstance 没有意义。</li>
     *   <li> 这种START_PROCESS类型的工作流事件触发的，在任务开始事件处理中会初始化任务实例</li>
     *   <li> {@link TaskStartLifecycleEventHandler#handle(IWorkflowExecutionRunnable, TaskStartLifecycleEvent)}</li>
     *   <li> {@link TaskExecutionRunnable#initializeFirstRunTaskInstance()}</li>
     *   <li> {@link }执行{@link WorkflowRunningStateAction}</li>
     *   <li> 任务执行相关: {@link TaskStartLifecycleEventHandler}</li>
     *   <li> {@link TaskExecutionRunnableBuilder.TaskExecutionRunnableBuilderBuilder#workflowExecutionGraph}</li>
     *   <li> 避免不必要的数据库写入</li>
     *   <li> 只在真正需要执行时才创建任务实例</li>
     *   <li> 区分新工作流启动与容错恢复的不同场景</li>
     * </ul>
     *
     * @param workflowExecuteContextBuilder 工作流执行上下文构建器，包含已构建的WorkflowGraph、WorkflowDefinition等
     * @see WorkflowGraphTopologyLogicalVisitor
     * @see TaskExecutionRunnable
     * @see TaskExecutionRunnable#initializeFirstRunTaskInstance()
     * @see TaskStartLifecycleEventHandler#handle(IWorkflowExecutionRunnable, TaskStartLifecycleEvent)
     * @see WorkflowRunningStateAction
     *
     */
    @Override
    protected void assembleWorkflowExecutionGraph(final WorkflowExecuteContextBuilder workflowExecuteContextBuilder) {
        // 获取静态工作流图（基于WorkflowDefinition构建，包含TaskDefinition和依赖关系）
        final IWorkflowGraph workflowGraph = workflowExecuteContextBuilder.getWorkflowGraph();
        
        // 创建空的工作流执行图实例
        // 该实例将在遍历过程中填充TaskExecutionRunnable和依赖关系
        final WorkflowExecutionGraph workflowExecutionGraph = new WorkflowExecutionGraph();
        
        /**
         * 定义处理每个遍历到的节点的Consumer函数
         * 该函数会在WorkflowGraphTopologyLogicalVisitor遍历WorkflowGraph时被调用
         * 
         * @param task 当前任务名称
         * @param successors 当前任务的后继任务名称集合
         */
        final BiConsumer<String, Set<String>> taskExecutionRunnableCreator = (task, successors) -> {
            // 为当前任务创建TaskExecutionRunnableBuilder
            final TaskExecutionRunnableBuilder taskExecutionRunnableBuilder =
                    TaskExecutionRunnableBuilder
                            .builder()
                            // 关键：所有TaskExecutionRunnable都引用同一个WorkflowExecutionGraph实例
                            // 这样每个任务都可以访问整个执行图，用于依赖检查和任务触发
                            .workflowExecutionGraph(workflowExecutionGraph)
                            // 工作流定义（包含工作流的元数据信息）
                            .workflowDefinition(workflowExecuteContextBuilder.getWorkflowDefinition())
                            // 项目信息（包含项目的基本信息和配置）
                            .project(workflowExecuteContextBuilder.getProject())
                            // 工作流实例（包含工作流实例的执行信息）
                            .workflowInstance(workflowExecuteContextBuilder.getWorkflowInstance())
                            // 任务定义（从WorkflowGraph中根据任务名称获取）
                            .taskDefinition(workflowGraph.getTaskNodeByName(task))
                            // 工作流事件总线（所有TaskExecutionRunnable共享同一个事件总线）
                            .workflowEventBus(workflowExecuteContextBuilder.getWorkflowEventBus())
                            // Spring应用上下文（用于获取各种Bean）
                            .applicationContext(applicationContext)
                            /**
                             * 注意：taskInstance为null，在首次触发时通过initializeFirstRunTaskInstance()创建
                             * {@link TaskStartLifecycleEventHandler#handle(IWorkflowExecutionRunnable, TaskStartLifecycleEvent)}
                             */
                            .build();
            
            // 创建TaskExecutionRunnable实例并添加到执行图中
            // addNode方法会将TaskExecutionRunnable添加到totalTaskExecuteRunnableMap，
            // 并初始化predecessors和successors的Map条目
            // 一个workflowExecutionGraph有多个TaskExecutionRunnable
            workflowExecutionGraph.addNode(new TaskExecutionRunnable(taskExecutionRunnableBuilder));
            
            // 添加任务之间的依赖关系（边）
            // addEdge方法会同时更新successors和predecessors Map
            workflowExecutionGraph.addEdge(task, successors);
        };

        // 创建工作流图拓扑逻辑访问器
        final WorkflowGraphTopologyLogicalVisitor workflowGraphTopologyLogicalVisitor =
                WorkflowGraphTopologyLogicalVisitor.builder()
                        // 设置任务依赖类型（从工作流实例获取）
                        // TASK_ONLY(0): 只运行当前任务
                        // TASK_PRE(1): 运行当前任务及前置任务
                        // TASK_POST(2): 运行当前任务及后续任务（默认）
                        .taskDependType(workflowExecuteContextBuilder.getWorkflowInstance().getTaskDependType())
                        // 设置静态工作流图
                        .onWorkflowGraph(workflowGraph)
                        // 设置起始节点列表
                        // 如果command_param中的startNodes为空，则使用所有起始节点（predecessors为空的节点）
                        .fromTask(parseStartNodesFromWorkflowInstance(workflowExecuteContextBuilder))
                        // 设置访问函数（处理每个节点的函数）
                        .doVisitFunction(taskExecutionRunnableCreator)
                        .build();
        
        // 执行拓扑遍历
        // 根据taskDependType选择遍历策略，按拓扑顺序遍历所有节点
        // 对每个节点调用taskExecutionRunnableCreator函数，创建TaskExecutionRunnable并添加到执行图
        workflowGraphTopologyLogicalVisitor.visit();

        // 将构建好的WorkflowExecutionGraph设置到WorkflowExecuteContextBuilder中
        // 后续会用于构建WorkflowExecutionRunnable
        workflowExecuteContextBuilder.setWorkflowExecutionGraph(workflowExecutionGraph);
    }

    /**
     * 合并命令参数和工作流参数
     * <p>
     * 将Command中的参数与WorkflowDefinition中的全局参数合并，生成最终的全局参数。
     * 如果存在重复的key，Command参数会覆盖WorkflowDefinition参数（Command参数优先级更高）。
     * </p>
     * <p>
     * 参数来源：
     * <ul>
     *   <li>Command参数：从command.getCommandParam()解析，通过ICommandParam.getCommandParams()获取</li>
     *   <li>工作流定义参数：从workflowDefinition.getGlobalParams()解析，JSON格式的Property数组</li>
     * </ul>
     * </p>
     * <p>
     * 合并规则：
     * <ol>
     *   <li>首先将工作流定义的全局参数放入finalParams Map</li>
     *   <li>然后将Command参数放入finalParams Map（如果key重复，会覆盖工作流定义参数）</li>
     *   <li>最后将finalParams的值转换为JSON字符串返回</li>
     * </ol>
     * </p>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>在assembleWorkflowInstance中调用，用于设置工作流实例的globalParams字段</li>
     *   <li>合并后的参数会传递给所有任务，用于参数替换和变量解析</li>
     * </ul>
     * </p>
     *
     * @param command 命令对象，包含commandParam字段（JSON格式）
     * @param workflowDefinition 工作流定义对象，包含globalParams字段（JSON格式）
     * @return 合并后的全局参数JSON字符串
     */
    private String mergeCommandParamsWithWorkflowParams(final Command command,
                                                        final WorkflowDefinition workflowDefinition) {
        // 从Command中解析命令参数
        // command.getCommandParam()是JSON字符串，解析为ICommandParam对象，然后获取commandParams列表
        final List<Property> commandParams =
                Optional.ofNullable(JSONUtils.parseObject(command.getCommandParam(), ICommandParam.class))
                        .map(ICommandParam::getCommandParams)
                        .orElse(null);
        
        // 从WorkflowDefinition中解析全局参数
        // workflowDefinition.getGlobalParams()是JSON字符串，解析为Property列表
        final List<Property> globalParamsList = JSONUtils.toList(workflowDefinition.getGlobalParams(), Property.class);
        
        // 用于存储最终合并后的参数，key为参数名，value为Property对象
        Map<String, Property> finalParams = new HashMap<>();
        
        // 首先将工作流定义的全局参数放入finalParams
        if (CollectionUtils.isNotEmpty(globalParamsList)) {
            globalParamsList.forEach(globalParam -> finalParams.put(globalParam.getProp(), globalParam));
        }
        
        // 然后将Command参数放入finalParams
        // 如果key重复，Command参数会覆盖工作流定义参数（Command参数优先级更高）
        if (CollectionUtils.isNotEmpty(commandParams)) {
            commandParams.forEach(commandParam -> finalParams.put(commandParam.getProp(), commandParam));
        }
        
        // 将finalParams的值转换为JSON字符串返回
        return JSONUtils.toJsonString(finalParams.values());
    }

    /**
     * 返回该处理器支持的命令类型
     * <p>
     * RunWorkflowCommandHandler专门处理START_PROCESS类型的命令，
     * 用于启动新的工作流实例。
     * </p>
     *
     * @return CommandType.START_PROCESS
     */
    @Override
    public CommandType commandType() {
        return CommandType.START_PROCESS;
    }
}
