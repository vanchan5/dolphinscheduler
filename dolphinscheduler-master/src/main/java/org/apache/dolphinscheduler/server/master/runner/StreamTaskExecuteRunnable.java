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

package org.apache.dolphinscheduler.server.master.runner;

import static org.apache.dolphinscheduler.common.constants.Constants.DEFAULT_WORKER_GROUP;

import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.common.enums.Flag;
import org.apache.dolphinscheduler.common.enums.Priority;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.dao.entity.Environment;
import org.apache.dolphinscheduler.dao.entity.ProcessDefinition;
import org.apache.dolphinscheduler.dao.entity.ProcessTaskRelation;
import org.apache.dolphinscheduler.dao.entity.Resource;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.mapper.ProcessTaskRelationMapper;
import org.apache.dolphinscheduler.dao.repository.TaskInstanceDao;
import org.apache.dolphinscheduler.plugin.task.api.TaskChannel;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.TaskPluginManager;
import org.apache.dolphinscheduler.plugin.task.api.enums.TaskExecutionStatus;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;
import org.apache.dolphinscheduler.plugin.task.api.model.ResourceInfo;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.api.parameters.ParametersNode;
import org.apache.dolphinscheduler.plugin.task.api.parameters.resource.ResourceParametersHelper;
import org.apache.dolphinscheduler.plugin.task.api.parser.ParamUtils;
import org.apache.dolphinscheduler.plugin.task.api.utils.LogUtils;
import org.apache.dolphinscheduler.remote.command.task.TaskExecuteRunningMessageAck;
import org.apache.dolphinscheduler.remote.command.task.TaskExecuteStartMessage;
import org.apache.dolphinscheduler.server.master.builder.TaskExecutionContextBuilder;
import org.apache.dolphinscheduler.server.master.cache.StreamTaskInstanceExecCacheManager;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.event.StateEventHandleError;
import org.apache.dolphinscheduler.server.master.event.StateEventHandleException;
import org.apache.dolphinscheduler.server.master.metrics.TaskMetrics;
import org.apache.dolphinscheduler.server.master.processor.queue.TaskEvent;
import org.apache.dolphinscheduler.server.master.runner.dispatcher.WorkerTaskDispatcher;
import org.apache.dolphinscheduler.server.master.runner.execute.DefaultTaskExecuteRunnable;
import org.apache.dolphinscheduler.server.master.runner.execute.DefaultTaskExecuteRunnableFactory;
import org.apache.dolphinscheduler.service.bean.SpringApplicationContext;
import org.apache.dolphinscheduler.service.process.ProcessService;
import org.apache.dolphinscheduler.spi.enums.ResourceType;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * stream task execute
 */
@Slf4j
public class StreamTaskExecuteRunnable implements Runnable {

    protected MasterConfig masterConfig;

    protected ProcessService processService;

    protected TaskInstanceDao taskInstanceDao;

    protected DefaultTaskExecuteRunnableFactory defaultTaskExecuteRunnableFactory;

    protected WorkerTaskDispatcher workerTaskDispatcher;

    protected ProcessTaskRelationMapper processTaskRelationMapper;

    protected TaskPluginManager taskPluginManager;

    private StreamTaskInstanceExecCacheManager streamTaskInstanceExecCacheManager;

    protected TaskDefinition taskDefinition;

    protected TaskInstance taskInstance;

    protected ProcessDefinition processDefinition;

    protected TaskExecuteStartMessage taskExecuteStartMessage;

    /**
     * task event queue
     */
    private final ConcurrentLinkedQueue<TaskEvent> taskEvents = new ConcurrentLinkedQueue<>();

    private TaskRunnableStatus taskRunnableStatus = TaskRunnableStatus.CREATED;

    public StreamTaskExecuteRunnable(TaskDefinition taskDefinition, TaskExecuteStartMessage taskExecuteStartMessage) {
        this.processService = SpringApplicationContext.getBean(ProcessService.class);
        this.masterConfig = SpringApplicationContext.getBean(MasterConfig.class);
        this.workerTaskDispatcher = SpringApplicationContext.getBean(WorkerTaskDispatcher.class);
        this.taskPluginManager = SpringApplicationContext.getBean(TaskPluginManager.class);
        this.processTaskRelationMapper = SpringApplicationContext.getBean(ProcessTaskRelationMapper.class);
        this.taskInstanceDao = SpringApplicationContext.getBean(TaskInstanceDao.class);
        this.streamTaskInstanceExecCacheManager =
                SpringApplicationContext.getBean(StreamTaskInstanceExecCacheManager.class);
        this.taskDefinition = taskDefinition;
        this.taskExecuteStartMessage = taskExecuteStartMessage;
    }

    public TaskInstance getTaskInstance() {
        return taskInstance;
    }

    @Override
    public void run() {
        // submit task
        processService.updateTaskDefinitionResources(taskDefinition);
        taskInstance = newTaskInstance(taskDefinition);
        taskInstanceDao.upsertTaskInstance(taskInstance);

        // add cache
        streamTaskInstanceExecCacheManager.cache(taskInstance.getId(), this);

        List<ProcessTaskRelation> processTaskRelationList =
                processTaskRelationMapper.queryByTaskCode(taskDefinition.getCode());
        long processDefinitionCode = processTaskRelationList.get(0).getProcessDefinitionCode();
        int processDefinitionVersion = processTaskRelationList.get(0).getProcessDefinitionVersion();
        processDefinition = processService.findProcessDefinition(processDefinitionCode, processDefinitionVersion);

        try {
            DefaultTaskExecuteRunnable taskExecuteRunnable =
                    defaultTaskExecuteRunnableFactory.createTaskExecuteRunnable(taskInstance);
            workerTaskDispatcher.dispatchTask(taskExecuteRunnable);
        } catch (Exception e) {
            log.error("Master dispatch task to worker error, taskInstanceName: {}", taskInstance.getName(), e);
            taskInstance.setState(TaskExecutionStatus.FAILURE);
            taskInstanceDao.upsertTaskInstance(taskInstance);
            return;
        }
        // set started flag
        taskRunnableStatus = TaskRunnableStatus.STARTED;
        log.info("Master success dispatch task to worker, taskInstanceName: {}, worker: {}", taskInstance.getId(),
                taskInstance.getHost());
    }

    public boolean isStart() {
        return TaskRunnableStatus.STARTED == taskRunnableStatus;
    }

    public boolean addTaskEvent(TaskEvent taskEvent) {
        if (taskInstance.getId() != taskEvent.getTaskInstanceId()) {
            log.info("state event would be abounded, taskInstanceId:{}, eventType:{}, state:{}",
                    taskEvent.getTaskInstanceId(), taskEvent.getEvent(), taskEvent.getState());
            return false;
        }
        taskEvents.add(taskEvent);
        return true;
    }

    public int eventSize() {
        return this.taskEvents.size();
    }

    /**
     * handle event
     */
    public void handleEvents() {
        if (!isStart()) {
            log.info(
                    "The stream task instance is not started, will not handle its state event, current state event size: {}",
                    taskEvents.size());
            return;
        }
        TaskEvent taskEvent = null;
        while (!this.taskEvents.isEmpty()) {
            try {
                taskEvent = this.taskEvents.peek();
                LogUtils.setTaskInstanceIdMDC(taskEvent.getTaskInstanceId());

                log.info("Begin to handle state event, {}", taskEvent);
                if (this.handleTaskEvent(taskEvent)) {
                    this.taskEvents.remove(taskEvent);
                }
            } catch (StateEventHandleError stateEventHandleError) {
                log.error("State event handle error, will remove this event: {}", taskEvent, stateEventHandleError);
                this.taskEvents.remove(taskEvent);
                ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
            } catch (StateEventHandleException stateEventHandleException) {
                log.error("State event handle error, will retry this event: {}",
                        taskEvent,
                        stateEventHandleException);
                ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
            } catch (Exception e) {
                // we catch the exception here, since if the state event handle failed, the state event will still keep
                // in the stateEvents queue.
                log.error("State event handle error, get a unknown exception, will retry this event: {}",
                        taskEvent,
                        e);
                ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
            } finally {
                LogUtils.removeWorkflowAndTaskInstanceIdMDC();
            }
        }
    }

    public TaskInstance newTaskInstance(TaskDefinition taskDefinition) {
        TaskInstance taskInstance = new TaskInstance();
        taskInstance.setTaskCode(taskDefinition.getCode());
        taskInstance.setTaskDefinitionVersion(taskDefinition.getVersion());
        taskInstance.setName(taskDefinition.getName());
        // task instance state
        taskInstance.setState(TaskExecutionStatus.SUBMITTED_SUCCESS);
        // set process instance id to 0
        taskInstance.setProcessInstanceId(0);
        taskInstance.setProjectCode(taskDefinition.getProjectCode());
        // task instance type
        taskInstance.setTaskType(taskDefinition.getTaskType().toUpperCase());
        // task instance whether alert
        taskInstance.setAlertFlag(Flag.NO);

        // task instance start time
        taskInstance.setStartTime(null);

        // task instance flag
        taskInstance.setFlag(Flag.YES);

        // task instance current retry times
        taskInstance.setRetryTimes(0);
        taskInstance.setMaxRetryTimes(taskDefinition.getFailRetryTimes());
        taskInstance.setRetryInterval(taskDefinition.getFailRetryInterval());

        // set task param
        taskInstance.setTaskParams(taskDefinition.getTaskParams());

        // set task group and priority
        taskInstance.setTaskGroupId(taskDefinition.getTaskGroupId());
        taskInstance.setTaskGroupPriority(taskDefinition.getTaskGroupPriority());

        // set task cpu quota and max memory
        taskInstance.setCpuQuota(taskDefinition.getCpuQuota());
        taskInstance.setMemoryMax(taskDefinition.getMemoryMax());

        // task instance priority
        taskInstance.setTaskInstancePriority(Priority.MEDIUM);
        if (taskDefinition.getTaskPriority() != null) {
            taskInstance.setTaskInstancePriority(taskDefinition.getTaskPriority());
        }

        // delay execution time
        taskInstance.setDelayTime(taskDefinition.getDelayTime());

        // task dry run flag
        taskInstance.setDryRun(taskExecuteStartMessage.getDryRun());

        taskInstance.setWorkerGroup(StringUtils.isBlank(taskDefinition.getWorkerGroup()) ? DEFAULT_WORKER_GROUP
                : taskDefinition.getWorkerGroup());
        taskInstance.setEnvironmentCode(
                taskDefinition.getEnvironmentCode() == 0 ? -1 : taskDefinition.getEnvironmentCode());

        if (!taskInstance.getEnvironmentCode().equals(-1L)) {
            Environment environment = processService.findEnvironmentByCode(taskInstance.getEnvironmentCode());
            if (Objects.nonNull(environment) && StringUtils.isNotEmpty(environment.getConfig())) {
                taskInstance.setEnvironmentConfig(environment.getConfig());
            }
        }

        if (taskInstance.getSubmitTime() == null) {
            taskInstance.setSubmitTime(new Date());
        }
        if (taskInstance.getFirstSubmitTime() == null) {
            taskInstance.setFirstSubmitTime(taskInstance.getSubmitTime());
        }

        taskInstance.setTaskExecuteType(taskDefinition.getTaskExecuteType());
        taskInstance.setExecutorId(taskExecuteStartMessage.getExecutorId());
        taskInstance.setExecutorName(taskExecuteStartMessage.getExecutorName());

        return taskInstance;
    }

    /**
     * get TaskExecutionContext
     *
     * @param taskInstance taskInstance
     * @return TaskExecutionContext
     */
    protected TaskExecutionContext getTaskExecutionContext(TaskInstance taskInstance) {
        int userId = taskDefinition == null ? 0 : taskDefinition.getUserId();
        String tenantCode = processService.getTenantForProcess(taskExecuteStartMessage.getTenantCode(), userId);

        // verify tenant is null
        if (StringUtils.isBlank(tenantCode)) {
            log.error("tenant not exists,task instance id : {}", taskInstance.getId());
            return null;
        }

        taskInstance.setResources(getResourceFullNames(taskInstance));

        TaskChannel taskChannel = taskPluginManager.getTaskChannel(taskInstance.getTaskType());
        ResourceParametersHelper resources = taskChannel.getResources(taskInstance.getTaskParams());

        AbstractParameters baseParam = taskPluginManager.getParameters(
                ParametersNode.builder()
                        .taskType(taskInstance.getTaskType())
                        .taskParams(taskInstance.getTaskParams())
                        .build());
        Map<String, Property> propertyMap = paramParsingPreparation(taskInstance, baseParam);
        TaskExecutionContext taskExecutionContext = TaskExecutionContextBuilder.get()
                .buildWorkflowInstanceHost(masterConfig.getMasterAddress())
                .buildTaskInstanceRelatedInfo(taskInstance)
                .buildTaskDefinitionRelatedInfo(taskDefinition)
                .buildResourceParametersInfo(resources)
                .buildBusinessParamsMap(new HashMap<>())
                .buildParamInfo(propertyMap)
                .create();

        taskExecutionContext.setTenantCode(tenantCode);
        taskExecutionContext.setProjectCode(processDefinition.getProjectCode());
        taskExecutionContext.setProcessDefineCode(processDefinition.getCode());
        taskExecutionContext.setProcessDefineVersion(processDefinition.getVersion());
        // process instance id default 0
        taskExecutionContext.setProcessInstanceId(0);

        return taskExecutionContext;
    }

    /**
     * get resource map key is full name and value is tenantCode
     */
    protected Map<String, String> getResourceFullNames(TaskInstance taskInstance) {
        Map<String, String> resourcesMap = new HashMap<>();
        AbstractParameters baseParam = taskPluginManager.getParameters(ParametersNode.builder()
                .taskType(taskInstance.getTaskType()).taskParams(taskInstance.getTaskParams()).build());
        if (baseParam != null) {
            List<ResourceInfo> projectResourceFiles = baseParam.getResourceFilesList();
            if (CollectionUtils.isNotEmpty(projectResourceFiles)) {

                // filter the resources that the resource id equals 0
                Set<ResourceInfo> oldVersionResources =
                        projectResourceFiles.stream().filter(t -> t.getId() == null).collect(Collectors.toSet());
                if (CollectionUtils.isNotEmpty(oldVersionResources)) {
                    oldVersionResources.forEach(t -> resourcesMap.put(t.getRes(),
                            processService.queryTenantCodeByResName(t.getRes(), ResourceType.FILE)));
                }

                // get the resource id in order to get the resource names in batch
                Stream<Integer> resourceIdStream = projectResourceFiles.stream().map(ResourceInfo::getId);
                Set<Integer> resourceIdsSet = resourceIdStream.collect(Collectors.toSet());

                if (CollectionUtils.isNotEmpty(resourceIdsSet)) {
                    Integer[] resourceIds = resourceIdsSet.toArray(new Integer[resourceIdsSet.size()]);

                    List<Resource> resources = processService.listResourceByIds(resourceIds);
                    resources.forEach(t -> resourcesMap.put(t.getFullName(),
                            processService.queryTenantCodeByResName(t.getFullName(), ResourceType.FILE)));
                }
            }
        }

        return resourcesMap;
    }

    protected boolean handleTaskEvent(TaskEvent taskEvent) throws StateEventHandleException, StateEventHandleError {
        measureTaskState(taskEvent);

        if (taskInstance.getState() == null) {
            throw new StateEventHandleError("Task state event handle error due to task state is null");
        }

        taskInstance.setStartTime(taskEvent.getStartTime());
        taskInstance.setHost(taskEvent.getWorkerAddress());
        taskInstance.setLogPath(taskEvent.getLogPath());
        taskInstance.setExecutePath(taskEvent.getExecutePath());
        taskInstance.setPid(taskEvent.getProcessId());
        taskInstance.setAppLink(taskEvent.getAppIds());
        taskInstance.setState(taskEvent.getState());
        taskInstance.setEndTime(taskEvent.getEndTime());
        taskInstance.setVarPool(taskEvent.getVarPool());
        processService.changeOutParam(taskInstance);
        taskInstanceDao.updateById(taskInstance);

        // send ack
        sendAckToWorker(taskEvent);

        if (taskInstance.getState().isFinished()) {
            streamTaskInstanceExecCacheManager.removeByTaskInstanceId(taskInstance.getId());
            log.info("The stream task instance is finish, taskInstanceId:{}, state:{}", taskInstance.getId(),
                    taskEvent.getState());
        }

        return true;
    }

    private void measureTaskState(TaskEvent taskEvent) {
        if (taskEvent == null || taskEvent.getState() == null) {
            // the event is broken
            log.warn("The task event is broken..., taskEvent: {}", taskEvent);
            return;
        }
        if (taskEvent.getState().isFinished()) {
            TaskMetrics.incTaskInstanceByState("finish");
        }
        switch (taskEvent.getState()) {
            case KILL:
                TaskMetrics.incTaskInstanceByState("stop");
                break;
            case SUCCESS:
                TaskMetrics.incTaskInstanceByState("success");
                break;
            case FAILURE:
                TaskMetrics.incTaskInstanceByState("fail");
                break;
            default:
                break;
        }
    }

    public Map<String, Property> paramParsingPreparation(@NonNull TaskInstance taskInstance,
                                                         @NonNull AbstractParameters parameters) {
        // assign value to definedParams here
        Map<String, String> globalParamsMap = taskExecuteStartMessage.getStartParams();
        Map<String, Property> globalParams = ParamUtils.getUserDefParamsMap(globalParamsMap);

        // combining local and global parameters
        Map<String, Property> localParams = parameters.getInputLocalParametersMap();

        // stream pass params
        parameters.setVarPool(taskInstance.getVarPool());
        Map<String, Property> varParams = parameters.getVarPoolMap();

        if (globalParams.isEmpty() && localParams.isEmpty() && varParams.isEmpty()) {
            return null;
        }

        if (varParams.size() != 0) {
            globalParams.putAll(varParams);
        }
        if (localParams.size() != 0) {
            globalParams.putAll(localParams);
        }

        return globalParams;
    }

    private void sendAckToWorker(TaskEvent taskEvent) {
        // If event handle success, send ack to worker to otherwise the worker will retry this event
        TaskExecuteRunningMessageAck taskExecuteRunningMessageAck =
                new TaskExecuteRunningMessageAck(
                        true,
                        taskEvent.getTaskInstanceId(),
                        masterConfig.getMasterAddress(),
                        taskEvent.getWorkerAddress(),
                        System.currentTimeMillis());
        taskEvent.getChannel().writeAndFlush(taskExecuteRunningMessageAck.convert2Command());
    }

    private enum TaskRunnableStatus {
        CREATED, STARTED,
        ;
    }
}
