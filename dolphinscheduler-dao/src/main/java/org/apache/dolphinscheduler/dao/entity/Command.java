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

package org.apache.dolphinscheduler.dao.entity;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.common.enums.FailureStrategy;
import org.apache.dolphinscheduler.common.enums.Priority;
import org.apache.dolphinscheduler.common.enums.TaskDependType;
import org.apache.dolphinscheduler.common.enums.WarningType;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 命令实体类，表示一个待执行的工作流命令
 * <p>
 * Command是工作流执行的入口实体，由调度器、API、手动触发等方式创建并插入数据库。
 * Master会定期从数据库中读取Command，通过WorkflowExecutionRunnableFactory将其转换为WorkflowExecutionRunnable进行执行。
 * </p>
 * <p>
 * Command的生命周期：
 * <ol>
 *   <li>创建：由API、调度器或手动触发创建，插入t_ds_command表</li>
 *   <li>读取：Master的CommandFetcher定期从数据库读取待执行的Command</li>
 *   <li>处理：WorkflowExecutionRunnableFactory使用事务删除Command，确保只处理一次</li>
 *   <li>转换：根据commandType选择合适的ICommandHandler将其转换为WorkflowExecutionRunnable</li>
 * </ol>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ds_command")
public class Command {

    /**
     * 命令ID（主键，自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 命令类型
     * <p>包括：
     * <ul>
     *   <li>START_PROCESS: 启动新的工作流实例</li>
     *   <li>STOP_PROCESS: 停止工作流实例</li>
     *   <li>PAUSE_PROCESS: 暂停工作流实例</li>
     *   <li>REPEAT_RUNNING: 重复运行工作流实例</li>
     *   <li>RECOVER_SUSPENDED_PROCESS: 恢复暂停的工作流实例</li>
     *   <li>START_FAILURE_TASK_PROCESS: 从失败任务开始重新运行</li>
     *   <li>COMPLEMENT_DATA: 补数</li>
     *   <li>SCHEDULER: 调度触发</li>
     *   <li>RECOVER_TOLERANCE_FAULT_PROCESS: 容错恢复</li>
     *   <li>RECOVER_WAITING_THREAD: 恢复等待线程</li>
     *   <li>START_CURRENT_TASK_PROCESS: 从当前任务开始</li>
     *   <li>DYNAMIC_GENERATION: 动态生成</li>
     * </ul>
     * </p>
     */
    @TableField("command_type")
    private CommandType commandType;

    /**
     * 工作流定义代码（唯一标识，64位长整型）
     * <p>用于标识要执行的工作流定义，对应t_ds_process_definition表的code字段</p>
     */
    @TableField("workflow_definition_code")
    private long workflowDefinitionCode;

    /**
     * 工作流定义版本号
     * <p>用于指定要执行的工作流定义的特定版本</p>
     */
    @TableField("workflow_definition_version")
    private int workflowDefinitionVersion;

    /**
     * 工作流实例ID
     * <p>对于某些命令类型（如STOP_PROCESS、PAUSE_PROCESS），需要指定具体的工作流实例ID</p>
     */
    @TableField("workflow_instance_id")
    private int workflowInstanceId;

    /**
     * 命令参数（JSON字符串格式）
     * <p>包含执行工作流所需的各种参数，如：
     * <ul>
     *   <li>补数参数：startDate、endDate等</li>
     *   <li>启动参数：用户自定义的启动参数</li>
     *   <li>时区信息：scheduleTimezone等</li>
     * </ul>
     * </p>
     */
    @TableField("command_param")
    private String commandParam;

    /**
     * 工作流实例优先级
     * <p>用于任务调度的优先级排序，优先级高的任务会被优先执行</p>
     */
    @TableField("workflow_instance_priority")
    private Priority workflowInstancePriority;

    /**
     * 执行者ID（已废弃）
     * <p>原用于指定执行任务的用户ID，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("executor_id")
    private int executorId;

    /**
     * 任务依赖类型（已废弃）
     * <p>原用于指定任务间的依赖关系，现从WorkflowDefinition中获取</p>
     */
    @Deprecated
    @TableField("task_depend_type")
    @Builder.Default
    private TaskDependType taskDependType = TaskDependType.TASK_POST;

    /**
     * 失败策略（已废弃）
     * <p>原用于指定任务失败后的处理策略，现从WorkflowDefinition中获取</p>
     */
    @Deprecated
    @TableField("failure_strategy")
    @Builder.Default
    private FailureStrategy failureStrategy = FailureStrategy.CONTINUE;

    /**
     * 告警类型（已废弃）
     * <p>原用于指定告警通知类型，现从WorkflowDefinition中获取</p>
     */
    @Deprecated
    @TableField("warning_type")
    private WarningType warningType;

    /**
     * 告警组ID（已废弃）
     * <p>原用于指定告警组，现从WorkflowDefinition中获取</p>
     */
    @Deprecated
    @TableField("warning_group_id")
    private Integer warningGroupId;

    /**
     * 调度时间（已废弃）
     * <p>原用于记录调度触发的时间，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("schedule_time")
    private Date scheduleTime;

    /**
     * 开始时间（已废弃）
     * <p>原用于记录命令创建时间，现不需要单独存储</p>
     */
    @Deprecated
    @TableField("start_time")
    private Date startTime = new Date();

    /**
     * 更新时间（已废弃）
     * <p>原用于记录命令更新时间，现不需要单独存储</p>
     */
    @Deprecated
    @TableField("update_time")
    @Builder.Default
    private Date updateTime = new Date();

    /**
     * Worker组名称（已废弃）
     * <p>原用于指定执行任务的Worker组，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("worker_group")
    private String workerGroup;

    /**
     * 租户代码（已废弃）
     * <p>原用于多租户隔离，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    private String tenantCode;

    /**
     * 环境代码（已废弃）
     * <p>原用于指定执行环境，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("environment_code")
    private Long environmentCode;

    /**
     * 干运行标志（已废弃）
     * <p>原用于标识是否为干运行模式，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("dry_run")
    private int dryRun;

    /**
     * 测试标志（已废弃）
     * <p>原用于标识是否为测试任务，现从WorkflowInstance中获取</p>
     */
    @Deprecated
    @TableField("test_flag")
    private int testFlag;

    public Command(
                   CommandType commandType,
                   TaskDependType taskDependType,
                   FailureStrategy failureStrategy,
                   int executorId,
                   long workflowDefinitionCode,
                   String commandParam,
                   WarningType warningType,
                   int warningGroupId,
                   Date scheduleTime,
                   String workerGroup,
                   Long environmentCode,
                   Priority workflowInstancePriority,
                   int dryRun,
                   int workflowInstanceId,
                   int workflowDefinitionVersion,
                   int testFlag) {
        this.commandType = commandType;
        this.executorId = executorId;
        this.workflowDefinitionCode = workflowDefinitionCode;
        this.commandParam = commandParam;
        this.warningType = warningType;
        this.warningGroupId = warningGroupId;
        this.scheduleTime = scheduleTime;
        this.taskDependType = taskDependType;
        this.failureStrategy = failureStrategy;
        this.startTime = new Date();
        this.updateTime = new Date();
        this.workerGroup = workerGroup;
        this.environmentCode = environmentCode;
        this.workflowInstancePriority = workflowInstancePriority;
        this.dryRun = dryRun;
        this.workflowInstanceId = workflowInstanceId;
        this.workflowDefinitionVersion = workflowDefinitionVersion;
        this.testFlag = testFlag;
    }
}
