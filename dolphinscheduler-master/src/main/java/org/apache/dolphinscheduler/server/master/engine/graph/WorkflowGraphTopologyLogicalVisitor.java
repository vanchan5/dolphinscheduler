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

import org.apache.dolphinscheduler.common.enums.TaskDependType;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;

import org.apache.commons.collections4.CollectionUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

/**
 * 工作流图拓扑逻辑访问器
 * <p>
 * WorkflowGraphTopologyLogicalVisitor用于按照拓扑顺序遍历工作流图（WorkflowGraph），
 * 并对遍历到的每个节点执行访问函数（visitFunction）。
 * </p>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>根据TaskDependType选择不同的遍历策略（TASK_ONLY、TASK_PRE、TASK_POST）</li>
 *   <li>使用拓扑排序算法确保按依赖顺序遍历节点</li>
 *   <li>通过visitFunction对每个节点执行自定义操作（如创建TaskExecutionRunnable）</li>
 * </ul>
 * </p>
 * <p>
 * 遍历策略（根据TaskDependType）：
 * <ul>
 *   <li><b>TASK_ONLY(0)</b>：只遍历起始节点，不遍历其前置或后继节点</li>
 *   <li><b>TASK_PRE(1)</b>：遍历起始节点及其所有前置节点（可达起始节点的节点）</li>
 *   <li><b>TASK_POST(2)</b>：遍历起始节点及其所有后继节点（从起始节点可达的节点，默认策略）</li>
 * </ul>
 * </p>
 * <p>
 * 拓扑排序算法：
 * <ul>
 *   <li>使用inDegreeMap记录每个节点的入度（前置节点数量）</li>
 *   <li>从入度为0的节点开始遍历（起始节点）</li>
 *   <li>每处理一个节点，将其所有后继节点的入度减1</li>
 *   <li>当后继节点的入度变为0时，将其加入待处理队列</li>
 *   <li>使用visitedTaskCodes记录已访问的节点，避免重复处理</li>
 * </ul>
 * </p>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>在RunWorkflowCommandHandler.assembleWorkflowExecutionGraph中，用于构建WorkflowExecutionGraph</li>
 *   <li>在WorkflowFailoverCommandHandler中，用于容错恢复时重建执行图</li>
 *   <li>在RecoverFailureTaskCommandHandler中，用于从失败节点恢复</li>
 * </ul>
 * </p>
 *
 * @see IWorkflowGraph
 * @see TaskDependType
 */
public class WorkflowGraphTopologyLogicalVisitor {

    /**
     * 静态工作流图
     * <p>基于WorkflowDefinition构建，包含TaskDefinition和任务之间的依赖关系</p>
     */
    private final IWorkflowGraph workflowGraph;

    /**
     * 任务依赖类型
     * <p>决定遍历策略：TASK_ONLY、TASK_PRE或TASK_POST</p>
     */
    private final TaskDependType taskDependType;

    /**
     * 起始节点集合
     * <p>
     * 如果构建时未指定startNodes，则使用workflowGraph.getStartNodes()（所有入度为0的节点）。
     * 如果指定了startNodes，则使用指定的节点集合。
     * </p>
     */
    private final Set<String> startNodes;

    /**
     * 访问函数
     * <p>
     * BiConsumer函数，接收两个参数：
     * <ul>
     *   <li>taskName：当前任务名称</li>
     *   <li>successors：当前任务的后继任务名称集合</li>
     * </ul>
     * 在遍历过程中，对每个节点调用此函数执行自定义操作（如创建TaskExecutionRunnable）。
     * </p>
     */
    private final BiConsumer<String, Set<String>> visitFunction;

    /**
     * 私有构造函数
     * <p>
     * 通过Builder模式构建，确保所有必需字段都被正确设置。
     * 如果未指定startNodes，则使用workflowGraph的所有起始节点。
     * </p>
     *
     * @param workflowGraphBfsVisitorBuilder Builder对象，包含所有构建参数
     */
    private WorkflowGraphTopologyLogicalVisitor(WorkflowGraphBfsVisitorBuilder workflowGraphBfsVisitorBuilder) {
        this.taskDependType = workflowGraphBfsVisitorBuilder.taskDependType;
        this.workflowGraph = checkNotNull(workflowGraphBfsVisitorBuilder.workflowGraph);
        this.visitFunction = checkNotNull(workflowGraphBfsVisitorBuilder.visitFunction);
        
        // 如果未指定起始节点，则使用工作流图的所有起始节点（入度为0的节点）
        if (CollectionUtils.isEmpty(workflowGraphBfsVisitorBuilder.startNodes)) {
            this.startNodes = new HashSet<>(workflowGraph.getStartNodes());
        } else {
            // 使用指定的起始节点列表
            this.startNodes = new HashSet<>(checkNotNull(workflowGraphBfsVisitorBuilder.startNodes));
        }
    }

    /**
     * 创建Builder实例
     * <p>使用Builder模式构建WorkflowGraphTopologyLogicalVisitor</p>
     *
     * @return Builder实例
     */
    public static WorkflowGraphBfsVisitorBuilder builder() {
        return new WorkflowGraphBfsVisitorBuilder();
    }

    /**
     * 执行拓扑遍历
     * <p>
     * 根据taskDependType选择不同的遍历策略，然后执行拓扑排序遍历。
     * 遍历过程中会对每个节点调用visitFunction函数。
     * </p>
     * <p>
     * 遍历策略：
     * <ul>
     *   <li>TASK_ONLY：只遍历起始节点</li>
     *   <li>TASK_PRE：遍历起始节点及其前置节点</li>
     *   <li>TASK_POST：遍历起始节点及其后继节点（默认）</li>
     * </ul>
     * </p>
     */
    public void visit() {
        switch (taskDependType) {
            case TASK_ONLY:
                // 只遍历起始节点，不遍历其前置或后继节点
                visitStartNodesOnly();
                break;
            case TASK_PRE:
                // 遍历起始节点及其所有前置节点（可达起始节点的节点）
                visitToStartNodes();
                break;
            case TASK_POST:
                // 遍历起始节点及其所有后继节点（从起始节点可达的节点，默认策略）
                visitFromStartNodes();
                break;
            default:
                throw new IllegalArgumentException("Unsupported task depend type: " + taskDependType);
        }
    }

    /**
     * 只访问起始节点
     * <p>
     * TASK_ONLY策略：只遍历startNodes中的节点，不遍历其前置或后继节点。
     * 将startNodes作为子图节点集合，然后执行拓扑遍历。
     * </p>
     */
    private void visitStartNodesOnly() {
        doVisitationInSubGraph(Sets.newHashSet(startNodes));
    }

    /**
     * 遍历起始节点及其前置节点
     * <p>
     * TASK_PRE策略：从起始节点开始，沿着前置节点方向（反向）BFS遍历，
     * 收集所有可以到达起始节点的节点（即起始节点的所有前置节点）。
     * 然后对收集到的子图节点执行拓扑遍历。
     * </p>
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>从startNodes开始，使用BFS遍历</li>
     *   <li>对每个节点，获取其前置节点（predecessors）</li>
     *   <li>将前置节点加入待遍历队列和子图节点集合</li>
     *   <li>重复直到队列为空，收集到所有前置节点</li>
     *   <li>对收集到的子图节点执行拓扑遍历</li>
     * </ol>
     * </p>
     * <p>
     * 示例：如果startNodes=[C]，且A->B->C，则subGraphNodes=[A, B, C]
     * </p>
     */
    private void visitToStartNodes() {
        // 从起始节点开始BFS遍历
        final LinkedList<String> bootstrapTaskCodes = new LinkedList<>(startNodes);
        final Set<String> subGraphNodes = new HashSet<>();
        
        while (!bootstrapTaskCodes.isEmpty()) {
            String taskName = bootstrapTaskCodes.removeFirst();
            
            // 如果节点已经在子图中，跳过（避免重复处理）
            if (subGraphNodes.contains(taskName)) {
                continue;
            }
            
            // 将节点加入子图节点集合
            subGraphNodes.add(taskName);
            
            // 获取当前节点的前置节点（predecessors）
            final Set<String> predecessors = workflowGraph.getPredecessors(taskName);
            // 将前置节点加入待遍历队列（继续向前追溯）
            bootstrapTaskCodes.addAll(predecessors);
        }
        
        // 对收集到的子图节点执行拓扑遍历
        doVisitationInSubGraph(subGraphNodes);
    }

    /**
     * 遍历起始节点及其后继节点
     * <p>
     * TASK_POST策略（默认策略）：从起始节点开始，沿着后继节点方向（正向）BFS遍历，
     * 收集所有从起始节点可达的节点（即起始节点的所有后继节点）。
     * 然后对收集到的子图节点执行拓扑遍历。
     * </p>
     * <p>
     * 执行步骤：
     * <ol>
     *   <li>从startNodes开始，使用BFS遍历</li>
     *   <li>对每个节点，获取其后继节点（successors）</li>
     *   <li>将后继节点加入待遍历队列和子图节点集合</li>
     *   <li>重复直到队列为空，收集到所有后继节点</li>
     *   <li>对收集到的子图节点执行拓扑遍历</li>
     * </ol>
     * </p>
     * <p>
     * 示例：如果startNodes=[A]，且A->B->C，则subGraphNodes=[A, B, C]
     * </p>
     * <p>
     * 这是最常用的策略，适用于从起始节点开始执行整个工作流的情况。
     * </p>
     */
    private void visitFromStartNodes() {
        // 从起始节点开始BFS遍历
        final LinkedList<String> bootstrapTaskCodes = new LinkedList<>(startNodes);
        final Set<String> subGraphNodes = new HashSet<>();
        
        while (!bootstrapTaskCodes.isEmpty()) {
            String taskName = bootstrapTaskCodes.removeFirst();
            
            // 如果节点已经在子图中，跳过（避免重复处理）
            if (subGraphNodes.contains(taskName)) {
                continue;
            }
            
            // 将节点加入子图节点集合
            subGraphNodes.add(taskName);
            
            // 获取当前节点的后继节点（successors）
            final Set<String> successors = workflowGraph.getSuccessors(taskName);
            // 将后继节点加入待遍历队列（继续向后遍历）
            bootstrapTaskCodes.addAll(successors);
        }
        
        // 对收集到的子图节点执行拓扑遍历
        doVisitationInSubGraph(subGraphNodes);
    }

    /**
     * 在子图中执行拓扑遍历
     * <p>
     * 核心方法，使用拓扑排序算法对子图中的节点进行遍历。
     * 只对subGraphNodes中的节点调用visitFunction，其他节点会被跳过。
     * </p>
     * <p>
     * 拓扑排序算法：
     * <ol>
     *   <li>构建inDegreeMap：记录每个节点的入度（前置节点数量）</li>
     *   <li>初始化队列：将所有入度为0的节点加入队列（起始节点）</li>
     *   <li>循环处理：
     *       <ul>
     *         <li>从队列中取出一个节点</li>
     *         <li>如果节点入度为0且未访问过，则调用visitFunction</li>
     *         <li>将该节点所有后继节点的入度减1</li>
     *         <li>如果后继节点入度变为0，将其加入队列</li>
     *       </ul>
     *   </li>
     * </ol>
     * </p>
     * <p>
     * 关键点：
     * <ul>
     *   <li>只有入度为0的节点才会被处理（确保按依赖顺序）</li>
     *   <li>使用visitedTaskCodes避免重复访问</li>
     *   <li>只有subGraphNodes中的节点才会调用visitFunction</li>
     *   <li>即使节点不在subGraphNodes中，也会更新其入度（保证拓扑排序正确）</li>
     * </ul>
     * </p>
     *
     * @param subGraphNodes 子图节点集合，只有这些节点会被visitFunction处理
     */
    private void doVisitationInSubGraph(Set<String> subGraphNodes) {
        // 构建入度Map：记录每个节点的入度（前置节点数量）
        // key: 任务名称, value: 入度（前置节点数量）
        Map<String, Integer> inDegreeMap = workflowGraph.getAllTaskNodes()
                .stream()
                .collect(Collectors.toMap(TaskDefinition::getName,
                        taskDefinition -> workflowGraph.getPredecessors(taskDefinition.getName()).size()));
        
        // 初始化队列：将所有入度为0的节点加入队列（起始节点）
        final LinkedList<String> bootstrapTaskCodes = inDegreeMap
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() == 0)  // 只选择入度为0的节点
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedList::new));
        
        // 访问标记集合，用于记录已访问的节点，避免重复处理
        Set<String> visitedTaskCodes = new HashSet<>();

        // 拓扑排序遍历：从入度为0的节点开始，按依赖顺序处理
        while (!bootstrapTaskCodes.isEmpty()) {
            String taskName = bootstrapTaskCodes.removeFirst();
            
            // 如果节点入度不为0，跳过（等待其前置节点处理完成）
            if (inDegreeMap.get(taskName) > 0) {
                continue;
            }
            
            // 只有当节点入度为0且未访问过时，才进行处理
            if (!visitedTaskCodes.contains(taskName)) {
                visitedTaskCodes.add(taskName);  // 标记为已访问
                
                // 获取当前节点的后继节点
                final Set<String> successors = workflowGraph.getSuccessors(taskName);
                
                // 只有subGraphNodes中的节点才会调用visitFunction
                // 即使节点不在subGraphNodes中，也会更新其入度（保证拓扑排序正确）
                if (subGraphNodes.contains(taskName)) {
                    // 调用访问函数，执行自定义操作（如创建TaskExecutionRunnable）
                    visitFunction.accept(taskName, successors);
                }
                
                // 更新所有后继节点的入度（减1）
                // 当前节点已经处理完成，其所有后继节点的前置依赖减少1
                for (String successor : successors) {
                    inDegreeMap.put(successor, inDegreeMap.get(successor) - 1);
                }
                
                // 将后继节点加入队列，等待处理
                // 如果后继节点的入度变为0，它会在下一次循环中被处理
                bootstrapTaskCodes.addAll(successors);
            }
        }
    }

    /**
     * Builder类：用于构建WorkflowGraphTopologyLogicalVisitor
     * <p>
     * 使用Builder模式，提供链式调用方式设置各种参数。
     * 所有方法都返回this，支持链式调用。
     * </p>
     */
    public static class WorkflowGraphBfsVisitorBuilder {

        /**
         * 静态工作流图
         */
        private IWorkflowGraph workflowGraph;

        /**
         * 起始节点列表
         * <p>如果为null或空，则使用workflowGraph的所有起始节点</p>
         */
        private List<String> startNodes;

        /**
         * 任务依赖类型
         * <p>默认值为TASK_POST（遍历起始节点及其后继节点）</p>
         */
        private TaskDependType taskDependType = TaskDependType.TASK_POST;

        /**
         * 访问函数
         * <p>在遍历过程中对每个节点调用的函数</p>
         */
        private BiConsumer<String, Set<String>> visitFunction;

        /**
         * 设置工作流图
         *
         * @param workflowGraph 静态工作流图
         * @return Builder实例，支持链式调用
         */
        public WorkflowGraphBfsVisitorBuilder onWorkflowGraph(IWorkflowGraph workflowGraph) {
            this.workflowGraph = workflowGraph;
            return this;
        }

        /**
         * 设置任务依赖类型
         *
         * @param taskDependType 任务依赖类型（TASK_ONLY、TASK_PRE或TASK_POST）
         * @return Builder实例，支持链式调用
         */
        public WorkflowGraphBfsVisitorBuilder taskDependType(TaskDependType taskDependType) {
            this.taskDependType = taskDependType;
            return this;
        }

        /**
         * 设置起始节点列表
         * <p>
         * 如果设置为null或空列表，则使用workflowGraph的所有起始节点（入度为0的节点）。
         * </p>
         *
         * @param startNodes 起始节点列表
         * @return Builder实例，支持链式调用
         */
        public WorkflowGraphBfsVisitorBuilder fromTask(List<String> startNodes) {
            this.startNodes = startNodes;
            return this;
        }

        /**
         * 设置访问函数
         * <p>
         * 访问函数会在遍历过程中对每个节点调用，接收两个参数：
         * <ul>
         *   <li>taskName：当前任务名称</li>
         *   <li>successors：当前任务的后继任务名称集合</li>
         * </ul>
         * </p>
         *
         * @param visitFunction 访问函数
         * @return Builder实例，支持链式调用
         */
        public WorkflowGraphBfsVisitorBuilder doVisitFunction(BiConsumer<String, Set<String>> visitFunction) {
            this.visitFunction = visitFunction;
            return this;
        }

        /**
         * 构建WorkflowGraphTopologyLogicalVisitor实例
         * <p>
         * 使用设置的参数创建Visitor实例。
         * workflowGraph和visitFunction是必需参数，如果为null会抛出异常。
         * </p>
         *
         * @return WorkflowGraphTopologyLogicalVisitor实例
         * @throws NullPointerException 如果workflowGraph或visitFunction为null
         */
        public WorkflowGraphTopologyLogicalVisitor build() {
            return new WorkflowGraphTopologyLogicalVisitor(this);
        }
    }
}
