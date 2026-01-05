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

package org.apache.dolphinscheduler.plugin.task.api;

import org.apache.dolphinscheduler.plugin.task.api.enums.TaskTimeoutStrategy;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;
import org.apache.dolphinscheduler.plugin.task.api.parameters.resource.ResourceParametersHelper;
import org.apache.dolphinscheduler.plugin.task.api.resource.ResourceContext;

import java.io.Serializable;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * to master/worker task transport
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskExecutionContext implements Serializable {

    private static final long serialVersionUID = -1L;

    /**
     * 任务实例ID
     */
    private int taskInstanceId;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 任务首次提交时间（时间戳，毫秒）
     */
    private long firstSubmitTime;

    /**
     * 任务开始时间（时间戳，毫秒）
     */
    private long startTime;

    /**
     * 任务类型（如：SQL、SHELL、PYTHON等）
     */
    private String taskType;

    /**
     * 工作流实例主机地址（Master地址）
     */
    private String workflowInstanceHost;

    /**
     * Worker主机地址，任务执行的Worker节点
     */
    private String host;

    /**
     * 任务执行工作目录路径
     */
    private String executePath;

    /**
     * 任务日志文件路径
     */
    private String logPath;

    /**
     * 应用程序信息文件路径
     */
    private String appInfoPath;

    /**
     * 任务执行进程ID
     */
    private int processId;

    /**
     * 工作流定义代码（唯一标识）
     */
    private Long workflowDefinitionCode;

    /**
     * 工作流定义版本
     */
    private int workflowDefinitionVersion;

    /**
     * 应用程序ID（如：Yarn application ID），多个时用逗号分隔
     */
    private String appIds;

    /**
     * 工作流实例ID
     */
    private int workflowInstanceId;

    /**
     * 工作流实例调度时间（时间戳，毫秒）
     */
    private long scheduleTime;

    /**
     * 全局参数（JSON字符串格式）
     */
    private String globalParams;

    /**
     * 执行者ID（执行任务的用户ID）
     */
    private int executorId;

    /**
     * 补数命令类型代码（如果工作流是补数命令）
     */
    private int cmdTypeIfComplement;

    /**
     * 租户代码，用于多租户隔离
     */
    private String tenantCode;

    /**
     * 工作流定义ID
     */
    private int workflowDefinitionId;

    /**
     * 项目ID
     */
    private int projectId;

    /**
     * 项目代码（唯一标识）
     */
    private long projectCode;

    /**
     * 任务参数（JSON字符串格式）
     */
    private String taskParams;

    /**
     * 环境配置脚本内容
     */
    private String environmentConfig;

    /**
     * 定义的参数映射（从任务参数和上下文中解析得到）
     * todo: we need to rename definedParams, prepareParamsMap, paramsMap, this is confusing
     */
    private Map<String, String> definedParams;

    /**
     * 预准备参数映射（全局参数和本地参数合并并解析后）
     * 用于任务执行前的参数替换
     */
    private Map<String, Property> prepareParamsMap;

    /**
     * 任务应用程序ID（已废弃，请使用taskInstanceId代替）
     */
    @Deprecated
    private String taskAppId;

    /**
     * 任务超时策略（WARN：警告，FAILED：失败，WARNFAILED：警告后失败）
     */
    private TaskTimeoutStrategy taskTimeoutStrategy;

    /**
     * 任务超时时长（单位：秒）
     */
    private int taskTimeout;

    /**
     * Worker组名称，用于任务执行
     */
    private String workerGroup;

    /**
     * 资源参数辅助类，用于管理任务资源（数据源、文件等）
     */
    private ResourceParametersHelper resourceParametersHelper;

    /**
     * 任务结束时间（时间戳，毫秒）
     */
    private long endTime;

    /**
     * SQL任务执行上下文（用于SQL任务）
     */
    private SQLTaskExecutionContext sqlTaskExecutionContext;

    /**
     * Kubernetes任务执行上下文（用于K8s任务）
     */
    private K8sTaskExecutionContext k8sTaskExecutionContext;

    /**
     * 资源上下文，包含下载的资源文件信息
     */
    private ResourceContext resourceContext;

    /**
     * 变量池（JSON字符串格式），包含上游任务的输出参数
     * 由所有直接前置任务的varPool合并得到
     */
    private String varPool;

    /**
     * 干运行标志：0表示正常执行，1表示干运行（模拟执行，不实际执行）
     */
    private int dryRun;

    /**
     * 业务参数映射（最终合并的参数：优先级 globalParams > varPool > localParams）
     */
    private Map<String, Property> paramsMap;

    /**
     * CPU配额限制（单位：核），用于任务执行（仅Linux系统）
     */
    private Integer cpuQuota;

    /**
     * 内存限制（单位：MB），用于任务执行（仅Linux系统）
     */
    private Integer memoryMax;

    /**
     * 测试标志：0表示正常任务，1表示测试任务
     */
    private int testFlag;

    /**
     * 是否启用日志缓冲区，用于任务日志输出
     */
    private boolean logBufferEnable;

    /**
     * 调度失败次数（任务调度失败的次数）
     */
    private int dispatchFailTimes;

    /**
     * 故障转移标志，表示是否为故障转移任务
     */
    private boolean failover;

    public int increaseDispatchFailTimes() {
        return ++dispatchFailTimes;
    }
}
