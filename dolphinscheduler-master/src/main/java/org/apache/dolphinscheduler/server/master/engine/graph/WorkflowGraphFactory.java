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

import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowTaskRelation;
import org.apache.dolphinscheduler.dao.repository.TaskDefinitionLogDao;
import org.apache.dolphinscheduler.service.process.ProcessService;

import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowGraphFactory {

    @Autowired
    private ProcessService processService;

    @Autowired
    private TaskDefinitionLogDao taskDefinitionLogDao;

    public IWorkflowGraph createWorkflowGraph(WorkflowDefinition workflowDefinition) {

        /**
         * 根据workflow_definition_code和workflow_definition_version
         * 查询t_ds_workflow_task_relation_log
         *
         * 转为WorkflowTaskRelation
         */
        List<WorkflowTaskRelation> workflowTaskRelations = processService.findRelationByCode(
                workflowDefinition.getCode(),
                workflowDefinition.getVersion());
        /**
         *  pre_task_code = 0 说明是开始节点,没有前置节点,post_task_code为自身code
         *  post_task_code != 0 说明有前置节点
         *  post_task_code != 0 说明有前置节点
         *  例子
         * select tdtdl.*
         * from t_ds_task_definition_log tdtdl
         * inner join t_ds_workflow_task_relation_log tdwtrl on tdwtrl.post_task_code = tdtdl.code and
         *                                                      tdwtrl.post_task_version = tdtdl.version
         * where workflow_definition_code = '160443746112000' and workflow_definition_version=3
         * and tdwtrl.post_task_code > 0
         */
        List<TaskDefinition> taskDefinitions = taskDefinitionLogDao.queryTaskDefineLogList(workflowTaskRelations)
                .stream()
                .map(TaskDefinition.class::cast)
                .collect(Collectors.toList());
        return new WorkflowGraph(workflowTaskRelations, taskDefinitions);
    }

}
