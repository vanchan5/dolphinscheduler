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

package org.apache.dolphinscheduler.server.master.engine.graph;

import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowTaskRelation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作流图实现类
 * <p>
 * WorkflowGraph基于工作流定义（WorkflowDefinition）构建，表示工作流的静态结构。
 * 它封装了任务定义（TaskDefinition）和任务之间的依赖关系，用于描述工作流的DAG结构。
 * </p>
 * <p>
 * 核心数据结构：
 * <ul>
 *   <li>taskDefinitionCodeMap: 通过任务代码（code）快速查找任务定义</li>
 *   <li>taskDefinitionMap: 通过任务名称（name）快速查找任务定义</li>
 *   <li>predecessors: 每个任务的前置任务列表（用于查询依赖关系）</li>
 *   <li>successors: 每个任务的后继任务列表（用于拓扑遍历）</li>
 * </ul>
 * </p>
 * <p>
 * 与WorkflowExecutionGraph的区别：
 * <ul>
 *   <li>WorkflowGraph: 基于TaskDefinition，描述工作流的静态结构</li>
 *   <li>WorkflowExecutionGraph: 基于TaskExecutionRunnable，包含任务执行状态，用于执行时管理</li>
 * </ul>
 * </p>
 */
public class WorkflowGraph implements IWorkflowGraph {

    /**
     * 任务代码到任务定义的映射
     * <p>Key: 任务代码（TaskDefinition.code），Value: 任务定义（TaskDefinition）</p>
     * <p>用于通过任务代码快速查找任务定义，主要用于addTaskEdge方法中根据pre_task_code和post_task_code查找任务</p>
     */
    private final Map<Long, TaskDefinition> taskDefinitionCodeMap;

    /**
     * 任务名称到任务定义的映射
     * <p>Key: 任务名称（TaskDefinition.name），Value: 任务定义（TaskDefinition）</p>
     * <p>用于通过任务名称快速查找任务定义，主要用于getTaskNodeByName方法</p>
     */
    private final Map<String, TaskDefinition> taskDefinitionMap;

    /**
     * 任务的前置任务映射
     * <p>Key: 任务名称（TaskDefinition.name），Value: 前置任务名称列表</p>
     * <p>用于查询某个任务的所有前置任务（依赖的任务），用于依赖检查和触发条件判断</p>
     * <p>例如：如果A -> B，那么predecessors中B对应的列表包含A</p>
     */
    private final Map<String, List<String>> predecessors;

    /**
     * 任务的后继任务映射
     * <p>Key: 任务名称（TaskDefinition.name），Value: 后继任务名称列表</p>
     * <p>用于查询某个任务的所有后继任务（被依赖的任务），用于拓扑遍历和任务触发</p>
     * <p>例如：如果A -> B，那么successors中A对应的列表包含B</p>
     */
    private final Map<String, List<String>> successors;

    /**
     * 构造函数：构建工作流图
     * <p>
     * 根据工作流任务关系列表和任务定义列表构建完整的工作流图结构。
     * 构建过程分为三个步骤：
     * <ol>
     *   <li>构建任务定义映射（taskDefinitionMap和taskDefinitionCodeMap）</li>
     *   <li>初始化所有任务节点的前置和后继列表（addTaskNodes）</li>
     *   <li>根据任务关系构建依赖关系（addTaskEdge）</li>
     * </ol>
     * </p>
     *
     * @param workflowTaskRelations 工作流任务关系列表，定义了任务之间的依赖关系
     *                             （从t_ds_workflow_task_relation_log表查询）
     * @param taskDefinitions 任务定义列表，包含所有任务的详细信息
     *                       （从t_ds_task_definition_log表查询，根据workflowTaskRelations中的code和version）
     * @throws IllegalArgumentException 如果taskDefinitions或workflowTaskRelations为null
     * @throws IllegalArgumentException 如果任务关系中的任务代码在taskDefinitions中不存在
     * @throws IllegalArgumentException 如果存在重复的任务关系
     */
    public WorkflowGraph(List<WorkflowTaskRelation> workflowTaskRelations, List<TaskDefinition> taskDefinitions) {
        checkNotNull(taskDefinitions, "taskDefinitions can not be null");
        checkNotNull(workflowTaskRelations, "taskDefinitions can not be null");
        
        // 初始化前置和后继映射
        this.predecessors = new HashMap<>();
        this.successors = new HashMap<>();

        // 构建任务定义的双重映射：通过名称和代码都可以快速查找
        this.taskDefinitionMap = taskDefinitions
                .stream()
                .collect(Collectors.toMap(TaskDefinition::getName, Function.identity()));
        this.taskDefinitionCodeMap = taskDefinitions
                .stream()
                .collect(Collectors.toMap(TaskDefinition::getCode, Function.identity()));

        // 添加所有任务节点（初始化predecessors和successors）
        addTaskNodes(taskDefinitions);
        
        // 添加任务边（构建依赖关系）
        addTaskEdge(workflowTaskRelations);
    }

    /**
     * 获取所有起始节点
     * <p>
     * 起始节点是指没有前置任务的任务（predecessors为空列表）。
     * 这些任务可以在工作流启动时立即执行，不需要等待其他任务完成。
     * </p>
     * <p>
     * 在数据库中的表示：WorkflowTaskRelation中pre_task_code=0的任务就是起始节点。
     * 但在构建图时，pre_task_code=0的关系会被跳过（只处理pre>0 AND post>0的情况），
     * 所以起始节点通过predecessors为空来判断。
     * </p>
     *
     * @return 起始节点名称列表，如果没有起始节点则返回空列表
     */
    @Override
    public List<String> getStartNodes() {
        return predecessors.entrySet()
                .stream()
                .filter(entry -> entry.getValue().isEmpty())  // 前置任务列表为空，说明是起始节点
                .map(Map.Entry::getKey)  // 提取任务名称
                .collect(Collectors.toList());
    }

    /**
     * 获取指定任务的所有前置任务
     * <p>
     * 前置任务是指当前任务依赖的任务，即执行当前任务之前必须先完成的任务。
     * 例如：如果A -> B，那么B的前置任务是A。
     * </p>
     *
     * @param taskName 任务名称
     * @return 前置任务名称集合，如果没有前置任务则返回空集合
     *         注意：如果taskName不存在，predecessors.get(taskName)返回null，new HashSet(null)会抛出异常
     */
    @Override
    public Set<String> getPredecessors(String taskName) {
        List<String> predList = predecessors.get(taskName);
        return predList != null ? new HashSet<>(predList) : new HashSet<>();
    }

    /**
     * 获取指定任务的所有后继任务
     * <p>
     * 后继任务是指依赖当前任务的任务，即当前任务完成后可能触发的任务。
     * 例如：如果A -> B，那么A的后继任务是B。
     * </p>
     *
     * @param taskName 任务名称
     * @return 后继任务名称集合，如果没有后继任务则返回空集合
     *         注意：如果taskName不存在，successors.get(taskName)返回null，new HashSet(null)会抛出异常
     */
    @Override
    public Set<String> getSuccessors(String taskName) {
        List<String> succList = successors.get(taskName);
        return succList != null ? new HashSet<>(succList) : new HashSet<>();
    }

    /**
     * 根据任务名称获取任务定义
     * <p>
     * 通过taskDefinitionMap快速查找任务定义。
     * 任务名称在同一个工作流定义内是唯一的。
     * </p>
     *
     * @param taskName 任务名称
     * @return 任务定义对象
     * @throws IllegalArgumentException 如果任务不存在
     */
    @Override
    public TaskDefinition getTaskNodeByName(String taskName) {
        TaskDefinition taskDefinition = taskDefinitionMap.get(taskName);
        if (taskDefinition == null) {
            throw new IllegalArgumentException("Cannot find task: " + taskName);
        }
        return taskDefinition;
    }

    /**
     * 根据任务代码获取任务定义
     * <p>
     * 通过taskDefinitionCodeMap快速查找任务定义。
     * 任务代码全局唯一，但在构建WorkflowGraph时，只包含当前工作流定义中的任务。
     * </p>
     * <p>
     * 主要用于addTaskEdge方法中，根据WorkflowTaskRelation中的pre_task_code和post_task_code查找任务。
     * </p>
     *
     * @param taskCode 任务代码（TaskDefinition.code）
     * @return 任务定义对象
     * @throws IllegalArgumentException 如果任务不存在
     */
    @Override
    public TaskDefinition getTaskNodeByCode(Long taskCode) {
        TaskDefinition taskDefinition = taskDefinitionCodeMap.get(taskCode);
        if (taskDefinition == null) {
            throw new IllegalArgumentException("Cannot find task: " + taskCode);
        }
        return taskDefinition;
    }

    /**
     * 获取所有任务节点
     * <p>
     * 返回工作流图中所有任务的TaskDefinition列表。
     * 注意：返回的是新的ArrayList，对返回列表的修改不会影响内部数据结构。
     * </p>
     *
     * @return 所有任务定义的列表
     */
    @Override
    public List<TaskDefinition> getAllTaskNodes() {
        return new ArrayList<>(taskDefinitionMap.values());
    }

    /**
     * 添加所有任务节点到图中
     * <p>
     * 为每个任务定义初始化前置和后继列表（初始为空）。
     * 这个方法在构建图的第一步执行，确保所有任务都在predecessors和successors中有对应的条目。
     * </p>
     * <p>
     * 执行后，predecessors和successors中都包含了所有任务，但列表都是空的。
     * 实际的依赖关系由addTaskEdge方法添加。
     * </p>
     *
     * @param taskDefinitions 任务定义列表
     * @throws IllegalArgumentException 如果任务名称重复（同一个任务被添加两次）
     */
    private void addTaskNodes(List<TaskDefinition> taskDefinitions) {
        taskDefinitions
                .stream()
                .map(TaskDefinition::getName)  // 提取任务名称
                .forEach(taskDefinition -> {
                    // 检查任务是否已存在（防止重复添加）
                    if (predecessors.containsKey(taskDefinition) || successors.containsKey(taskDefinition)) {
                        throw new IllegalArgumentException("The task " + taskDefinition + " is already exists");
                    }
                    // 初始化前置任务列表（空列表）
                    predecessors.put(taskDefinition, new ArrayList<>());
                    // 初始化后继任务列表（空列表）
                    successors.put(taskDefinition, new ArrayList<>());
                });
    }

    /**
     * 添加任务边（依赖关系）到图中
     * <p>
     * 根据WorkflowTaskRelation列表构建任务之间的依赖关系。
     * 每条关系表示一条有向边：pre_task_code -> post_task_code（前置任务 -> 后置任务）。
     * </p>
     * <p>
     * 处理逻辑：
     * <ul>
     *   <li>pre_task_code=0：表示开始节点（无前置任务），跳过处理（只处理pre>0 AND post>0的情况）</li>
     *   <li>pre>0 AND post>0：正常依赖关系，添加到predecessors和successors中</li>
     *   <li>pre<=0 AND post<=0：无效关系，抛出异常</li>
     * </ul>
     * </p>
     * <p>
     * 对于每条有效关系（pre -> post）：
     * <ul>
     *   <li>在predecessors[post]中添加pre（post的前置任务列表增加pre）</li>
     *   <li>在successors[pre]中添加post（pre的后继任务列表增加post）</li>
     * </ul>
     * </p>
     * <p>
     * 示例：如果关系是A -> B（pre=A的code，post=B的code）：
     * <ul>
     *   <li>predecessors[B.getName()].add(A.getName())</li>
     *   <li>successors[A.getName()].add(B.getName())</li>
     * </ul>
     * </p>
     *
     * @param workflowTaskRelations 工作流任务关系列表
     * @throws IllegalArgumentException 如果任务代码在taskDefinitionCodeMap中不存在
     * @throws IllegalArgumentException 如果存在重复的任务关系
     * @throws IllegalArgumentException 如果关系无效（pre<=0 AND post<=0）
     */
    private void addTaskEdge(List<WorkflowTaskRelation> workflowTaskRelations) {
        for (WorkflowTaskRelation workflowTaskRelation : workflowTaskRelations) {
            long pre = workflowTaskRelation.getPreTaskCode();
            long post = workflowTaskRelation.getPostTaskCode();

            // 只处理有效的任务依赖关系（pre>0 AND post>0）
            // pre_task_code=0表示开始节点，在addTaskEdge中跳过，通过getStartNodes()识别
            if (pre > 0 && post > 0) {

                // 验证前置任务是否存在
                if (!taskDefinitionCodeMap.containsKey(pre)) {
                    throw new IllegalArgumentException("Cannot find task: " + pre);
                }
                // 验证后置任务是否存在
                if (!taskDefinitionCodeMap.containsKey(post)) {
                    throw new IllegalArgumentException("Cannot find task: " + post);
                }
                
                // 获取前置任务和后置任务的TaskDefinition
                TaskDefinition preTask = checkNotNull(taskDefinitionCodeMap.get(pre), "Cannot find task: " + pre);
                TaskDefinition postTask = checkNotNull(taskDefinitionCodeMap.get(post), "Cannot find task: " + pre);
                
                // 在后置任务的前置任务列表中添加前置任务
                // 例如：A -> B，则predecessors[B].add(A)
                // 注意：predecessorsTasks是局部变量，但它引用的是predecessors Map中存储的List对象
                // 在Java中，对象通过引用传递，所以修改predecessorsTasks实际上是在修改Map中的List
                List<String> predecessorsTasks = predecessors.get(postTask.getName());
                if (predecessorsTasks.contains(preTask.getName())) {
                    throw new IllegalArgumentException("The task relation from " + preTask.getName() + " to "
                            + postTask.getName() + " is already exists");
                }
                predecessorsTasks.add(preTask.getName());  // 这里add操作会直接修改predecessors Map中的List

                // 在前置任务的后继任务列表中添加后置任务
                // 例如：A -> B，则successors[A].add(B)
                // 注意：successTasks是局部变量，但它引用的是successors Map中存储的List对象
                // 在Java中，对象通过引用传递，所以修改successTasks实际上是在修改Map中的List
                List<String> successTasks = successors.get(preTask.getName());
                if (successTasks.contains(postTask.getName())) {
                    throw new IllegalArgumentException("The task relation from " + preTask.getName() + " to "
                            + postTask.getName() + " is already exists");
                }
                successTasks.add(postTask.getName());  // 这里add操作会直接修改successors Map中的List
            }

            // 检查无效关系（pre<=0 AND post<=0）
            // 注意：pre=0 AND post>0是合法的（起始节点），不会被这里捕获
            if (pre <= 0 && post <= 0) {
                throw new IllegalArgumentException("The task relation from " + pre + " to " + post + " is invalid");
            }

        }
    }
}
