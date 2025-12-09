# 手动执行任务完整流程：从 API 到 Master/Worker 执行

## 流程概览

手动执行任务的完整流程从 API 接口开始，经过 Master 处理，最终由 Worker 执行：

```
API 接口 (start-workflow-instance)
  ↓
API Service (ExecutorServiceImpl)
  ↓
RPC 调用 Master (TriggerWorkflowExecutorDelegate)
  ↓
Master RPC Server (WorkflowControlClient)
  ↓
创建工作流实例和 Command (WorkflowManualTrigger)
  ↓
CommandEngine 循环处理 Command
  ↓
创建工作流执行 Runnable (WorkflowExecutionRunnableFactory)
  ↓
构建任务执行图 (RunWorkflowCommandHandler)
  ↓
发布工作流启动事件 (WorkflowStartLifecycleEvent)
  ↓
创建任务实例并提交调度
  ↓
Master 调度任务到 Worker
  ↓
Worker 执行任务
```

---

## 阶段一：API 接口接收请求

### 1.1 接口定义

**位置**: `ExecutorController.triggerWorkflowDefinition()`

**接口**: `POST /projects/{projectCode}/executors/start-workflow-instance`

**关键参数**:
- `workflowDefinitionCode`: 工作流定义编码（必填）
- `scheduleTime`: 调度时间（必填）
- `execType`: 执行类型，默认 `START_PROCESS`（手动执行）
- `startParams`: 启动参数（可选）
- `failureStrategy`: 失败策略
- `warningType`: 告警类型
- 其他配置参数...

### 1.2 请求处理

```java
@PostMapping(value = "start-workflow-instance")
public Result<List<Integer>> triggerWorkflowDefinition(
    @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
    @RequestParam(value = "workflowDefinitionCode") long workflowDefinitionCode,
    @RequestParam(value = "scheduleTime") String scheduleTime,
    @RequestParam(value = "execType", required = false, defaultValue = "START_PROCESS") CommandType execType,
    // ... 其他参数
) {
    switch (execType) {
        case START_PROCESS:
            // 手动执行
            WorkflowTriggerRequest request = WorkflowTriggerRequest.builder()
                .loginUser(loginUser)
                .workflowDefinitionCode(workflowDefinitionCode)
                .execType(execType)
                // ... 构建请求
                .build();
            return Result.success(Lists.newArrayList(
                execService.triggerWorkflowDefinition(request)
            ));
        // ... 其他类型
    }
}
```

---

## 阶段二：API Service 处理

### 2.1 Service 方法

**位置**: `ExecutorServiceImpl.triggerWorkflowDefinition()`

```java
@Override
@Transactional
public Integer triggerWorkflowDefinition(final WorkflowTriggerRequest triggerRequest) {
    // 1. 转换请求对象
    final TriggerWorkflowDTO triggerWorkflowDTO = 
        triggerWorkflowRequestTransformer.transform(triggerRequest);
    
    // 2. 验证请求
    triggerWorkflowDTOValidator.validate(triggerWorkflowDTO);
    
    // 3. 委托给 ExecutorClient 执行
    return executorClient.triggerWorkflowDefinition().execute(triggerWorkflowDTO);
}
```

### 2.2 ExecutorClient 委托

**位置**: `TriggerWorkflowExecutorDelegate.execute()`

```java
@Override
public Integer execute(final TriggerWorkflowDTO triggerWorkflowDTO) {
    // 1. 从注册中心获取 Master 服务器
    final Server masterServer = registryClient
        .getRandomServer(RegistryNodeType.MASTER)
        .orElse(null);
    
    if (masterServer == null) {
        throw new ServiceException("no master server available");
    }
    
    // 2. 转换请求对象
    WorkflowManualTriggerRequest request = 
        transform2WorkflowTriggerRequest(triggerWorkflowDTO);
    
    // 3. 通过 RPC 调用 Master
    final WorkflowManualTriggerResponse response = Clients
        .withService(IWorkflowControlClient.class)
        .withHost(masterServer.getHost() + ":" + masterServer.getPort())
        .manualTriggerWorkflow(request);
    
    // 4. 检查响应
    if (!response.isSuccess()) {
        throw new ServiceException("Trigger workflow failed: " + response.getMessage());
    }
    
    // 5. 返回工作流实例 ID
    return response.getWorkflowInstanceId();
}
```

**关键点**:
- 使用注册中心（Registry）获取可用的 Master 服务器
- 通过 Netty RPC 调用 Master 的 `IWorkflowControlClient.manualTriggerWorkflow()` 方法
- 返回创建的工作流实例 ID

---

## 阶段三：Master RPC 接收请求

### 3.1 RPC 接口实现

**位置**: `WorkflowControlClient.manualTriggerWorkflow()`

```java
@Override
public WorkflowManualTriggerResponse manualTriggerWorkflow(
    final WorkflowManualTriggerRequest manualTriggerRequest) {
    try {
        // 委托给 WorkflowManualTrigger 处理
        return workflowManualTrigger.triggerWorkflow(manualTriggerRequest);
    } catch (Exception ex) {
        log.error("Handle workflowTriggerRequest: {} failed", manualTriggerRequest, ex);
        return WorkflowManualTriggerResponse.fail(
            "Trigger workflow failed: " + ExceptionUtils.getMessage(ex));
    }
}
```

### 3.2 触发工作流

**位置**: `WorkflowManualTrigger.triggerWorkflow()`

继承自 `AbstractWorkflowTrigger`，执行以下步骤：

```java
@Override
@Transactional
public TriggerResponse triggerWorkflow(final TriggerRequest triggerRequest) {
    // 1. 构建工作流实例
    final WorkflowInstance workflowInstance = 
        constructWorkflowInstance(triggerRequest);
    
    // 2. 保存工作流实例到数据库
    workflowInstanceDao.insert(workflowInstance);
    
    // 3. 构建 Command
    final Command command = 
        constructTriggerCommand(triggerRequest, workflowInstance);
    
    // 4. 保存 Command 到数据库
    commandDao.insert(command);
    
    // 5. 返回成功响应
    return onTriggerSuccess(workflowInstance);
}
```

### 3.3 构建工作流实例

**位置**: `WorkflowManualTrigger.constructWorkflowInstance()`

```java
@Override
protected WorkflowInstance constructWorkflowInstance(
    final WorkflowManualTriggerRequest request) {
    
    // 1. 获取工作流定义
    final WorkflowDefinition workflowDefinition = 
        getProcessDefinition(request.getWorkflowDefinitionCode(), 
                           request.getWorkflowDefinitionVersion());
    
    // 2. 创建工作流实例对象
    final WorkflowInstance workflowInstance = new WorkflowInstance();
    workflowInstance.setWorkflowDefinitionCode(workflowDefinition.getCode());
    workflowInstance.setWorkflowDefinitionVersion(workflowDefinition.getVersion());
    workflowInstance.setProjectCode(workflowDefinition.getProjectCode());
    workflowInstance.setCommandType(CommandType.START_PROCESS);
    workflowInstance.setStateWithDesc(
        WorkflowExecutionStatus.SUBMITTED_SUCCESS, 
        CommandType.START_PROCESS.name());
    workflowInstance.setStartTime(new Date());
    workflowInstance.setRunTimes(1);
    workflowInstance.setName(String.join("-", 
        workflowDefinition.getName(), 
        DateUtils.getCurrentTimeStamp()));
    // ... 设置其他属性
    
    return workflowInstance;
}
```

**数据存储**:
- 工作流实例保存到 `t_ds_process_instance` 表
- 状态：`SUBMITTED_SUCCESS`

### 3.4 构建 Command

**位置**: `WorkflowManualTrigger.constructTriggerCommand()`

```java
@Override
protected Command constructTriggerCommand(
    final WorkflowManualTriggerRequest request,
    final WorkflowInstance workflowInstance) {
    
    // 构建 Command 参数
    final RunWorkflowCommandParam commandParam = RunWorkflowCommandParam.builder()
        .commandParams(request.getStartParamList())  // 启动参数
        .startNodes(request.getStartNodes())         // 起始节点
        .timeZone(DateUtils.getTimezone())
        .build();
    
    // 构建 Command 对象
    return Command.builder()
        .commandType(CommandType.START_PROCESS)
        .workflowDefinitionCode(request.getWorkflowDefinitionCode())
        .workflowDefinitionVersion(request.getWorkflowDefinitionVersion())
        .workflowInstanceId(workflowInstance.getId())
        .workflowInstancePriority(workflowInstance.getWorkflowInstancePriority())
        .commandParam(JSONUtils.toJsonString(commandParam))
        .build();
}
```

**数据存储**:
- Command 保存到 `t_ds_command` 表
- CommandType：`START_PROCESS`

---

## 阶段四：CommandEngine 处理 Command

### 4.1 CommandEngine 循环

**位置**: `CommandEngine.run()`

CommandEngine 是一个后台线程，循环从数据库获取 Command 并处理：

```java
@Override
public void run() {
    while (flag) {
        try {
            // 1. 检查服务器负载
            if (serverLoadProtection.isOverload(systemMetrics)) {
                Thread.sleep(Constants.SLEEP_TIME_MILLIS);
                continue;
            }
            
            // 2. 从数据库获取 Command
            List<Command> commands = commandFetcher.fetchCommands();
            
            if (CollectionUtils.isEmpty(commands)) {
                Thread.sleep(Constants.SLEEP_TIME_MILLIS);
                continue;
            }
            
            // 3. 异步处理每个 Command
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Command command : commands) {
                CompletableFuture<Void> future = bootstrapCommand(command)
                    .thenAccept(this::bootstrapWorkflowExecutionRunnable)
                    .thenAccept((unused) -> bootstrapSuccess(command))
                    .exceptionally(throwable -> bootstrapError(command, throwable));
                futures.add(future);
            }
            
            // 4. 等待所有 Command 处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Master schedule workflow error", e);
            ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
        }
    }
}
```

### 4.2 创建 WorkflowExecutionRunnable

**位置**: `CommandEngine.bootstrapCommand()`

```java
private CompletableFuture<IWorkflowExecutionRunnable> bootstrapCommand(Command command) {
    return supplyAsync(
        () -> workflowExecutionRunnableFactory.createWorkflowExecuteRunnable(command),
        commandHandleThreadPool);
}
```

**位置**: `WorkflowExecutionRunnableFactory.createWorkflowExecuteRunnable()`

```java
@Transactional
public IWorkflowExecutionRunnable createWorkflowExecuteRunnable(Command command) {
    // 1. 删除 Command（确保只处理一次，防止重复处理）
    deleteCommandOrThrow(command);
    
    // 2. 根据 CommandType 选择对应的 CommandHandler
    return doCreateWorkflowExecutionRunnable(command);
}

private IWorkflowExecutionRunnable doCreateWorkflowExecutionRunnable(Command command) {
    final CommandType commandType = command.getCommandType();
    
    // 查找对应的 CommandHandler
    final ICommandHandler commandHandler = commandHandlers.stream()
        .filter(c -> c.commandType() == commandType)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "Cannot find ICommandHandler for commandType: " + commandType));
    
    // 处理 Command，创建 WorkflowExecutionRunnable
    return commandHandler.handleCommand(command);
}
```

**关键点**:
- 使用 `@Transactional` 确保 Command 只被处理一次
- 通过删除 Command 来防止重复处理（如果删除失败，说明已被其他 Master 处理）

### 4.3 CommandHandler 处理 Command

**位置**: `RunWorkflowCommandHandler.handleCommand()`

对于 `START_PROCESS` 类型的 Command，使用 `RunWorkflowCommandHandler` 处理：

```java
@Override
public WorkflowExecutionRunnable handleCommand(final Command command) {
    final WorkflowExecuteContextBuilder builder = WorkflowExecuteContext.builder()
        .withCommand(command);
    
    // 1. 组装工作流定义
    assembleWorkflowDefinition(builder);
    
    // 2. 组装项目信息
    assembleProject(builder);
    
    // 3. 组装工作流图
    assembleWorkflowGraph(builder);
    
    // 4. 组装工作流实例（更新状态为 RUNNING_EXECUTION）
    assembleWorkflowInstance(builder);
    
    // 5. 组装生命周期监听器
    assembleWorkflowInstanceLifecycleListeners(builder);
    
    // 6. 组装事件总线
    assembleWorkflowEventBus(builder);
    
    // 7. 组装任务执行图（创建 TaskExecutionRunnable）
    assembleWorkflowExecutionGraph(builder);
    
    // 8. 创建 WorkflowExecutionRunnable
    return new WorkflowExecutionRunnable(workflowExecutionRunnableBuilder);
}
```

### 4.4 组装工作流实例

**位置**: `RunWorkflowCommandHandler.assembleWorkflowInstance()`

```java
@Override
protected void assembleWorkflowInstance(final WorkflowExecuteContextBuilder builder) {
    final Command command = builder.getCommand();
    
    // 从数据库获取工作流实例
    final WorkflowInstance workflowInstance = 
        workflowInstanceDao.queryById(command.getWorkflowInstanceId());
    
    // 更新状态为 RUNNING_EXECUTION
    workflowInstance.setStateWithDesc(
        WorkflowExecutionStatus.RUNNING_EXECUTION, 
        command.getCommandType().name());
    
    // 设置 Master 地址
    workflowInstance.setHost(masterConfig.getMasterAddress());
    
    // 设置命令参数
    workflowInstance.setCommandParam(command.getCommandParam());
    
    // 合并全局参数
    workflowInstance.setGlobalParams(
        mergeCommandParamsWithWorkflowParams(command, workflowDefinition));
    
    // 更新数据库
    workflowInstanceDao.upsertWorkflowInstance(workflowInstance);
    
    builder.setWorkflowInstance(workflowInstance);
}
```

**数据更新**:
- 工作流实例状态：`SUBMITTED_SUCCESS` → `RUNNING_EXECUTION`
- 设置 Master 地址

### 4.5 组装任务执行图

**位置**: `RunWorkflowCommandHandler.assembleWorkflowExecutionGraph()`

```java
@Override
protected void assembleWorkflowExecutionGraph(final WorkflowExecuteContextBuilder builder) {
    final IWorkflowGraph workflowGraph = builder.getWorkflowGraph();
    final WorkflowExecutionGraph executionGraph = new WorkflowExecutionGraph();
    
    // 任务执行 Runnable 创建器
    final BiConsumer<String, Set<String>> taskCreator = (task, successors) -> {
        // 为每个任务节点创建 TaskExecutionRunnable
        final TaskExecutionRunnableBuilder taskBuilder = TaskExecutionRunnableBuilder
            .builder()
            .workflowExecutionGraph(executionGraph)
            .workflowDefinition(builder.getWorkflowDefinition())
            .project(builder.getProject())
            .workflowInstance(builder.getWorkflowInstance())
            .taskDefinition(workflowGraph.getTaskNodeByName(task))
            .workflowEventBus(builder.getWorkflowEventBus())
            .applicationContext(applicationContext)
            .build();
        
        // 添加到执行图
        executionGraph.addNode(new TaskExecutionRunnable(taskBuilder));
        executionGraph.addEdge(task, successors);
    };
    
    // 遍历工作流图，创建任务执行 Runnable
    final WorkflowGraphTopologyLogicalVisitor visitor = 
        WorkflowGraphTopologyLogicalVisitor.builder()
            .taskDependType(builder.getWorkflowInstance().getTaskDependType())
            .onWorkflowGraph(workflowGraph)
            .fromTask(parseStartNodesFromWorkflowInstance(builder))
            .doVisitFunction(taskCreator)
            .build();
    
    visitor.visit();
    
    builder.setWorkflowExecutionGraph(executionGraph);
}
```

**关键点**:
- 为工作流中的每个任务节点创建 `TaskExecutionRunnable`
- 构建任务依赖关系图
- 此时任务实例还未创建，只是创建了 Runnable 对象

### 4.6 启动工作流执行

**位置**: `CommandEngine.bootstrapWorkflowExecutionRunnable()`

```java
private CompletableFuture<Void> bootstrapWorkflowExecutionRunnable(
    IWorkflowExecutionRunnable workflowExecutionRunnable) {
    
    final WorkflowInstance workflowInstance = 
        workflowExecutionRunnable.getWorkflowExecuteContext().getWorkflowInstance();
    
    // 检查状态（如果是串行等待，则不启动）
    if (workflowInstance.getState() == WorkflowExecutionStatus.SERIAL_WAIT) {
        return CompletableFuture.completedFuture(null);
    }
    
    // 1. 保存到工作流仓库
    workflowRepository.put(workflowExecutionRunnable);
    
    // 2. 注册事件总线
    workflowEventBusCoordinator.registerWorkflowEventBus(workflowExecutionRunnable);
    
    // 3. 发布工作流启动事件
    workflowExecutionRunnable.getWorkflowEventBus()
        .publish(WorkflowStartLifecycleEvent.of(workflowExecutionRunnable));
    
    return CompletableFuture.completedFuture(null);
}
```

---

## 阶段五：工作流启动事件处理

### 5.1 事件监听器

**位置**: `WorkflowStartLifecycleEventHandler`

当 `WorkflowStartLifecycleEvent` 发布后，事件监听器会处理：

```java
// 工作流启动事件会触发任务启动
// 通过事件总线机制，WorkflowExecutionRunnable 会检查哪些任务可以启动
// 并发布 TaskStartLifecycleEvent
```

### 5.2 任务启动事件

**位置**: `TaskStartLifecycleEventHandler`

```java
// 1. 创建任务实例
taskExecutionRunnable.initializeFirstRunTaskInstance();

// 2. 保存任务实例到数据库
taskInstanceDao.upsertTaskInstance(taskInstance);

// 3. 提交任务到调度队列
globalTaskDispatchWaitingQueue.addTask(taskExecutionRunnable);
```

### 5.3 创建任务实例

**位置**: `FirstRunTaskInstanceFactory`

```java
// 创建首次运行的任务实例
TaskInstance taskInstance = new TaskInstance();
taskInstance.setTaskCode(taskDefinition.getCode());
taskInstance.setTaskName(taskDefinition.getName());
taskInstance.setTaskType(taskDefinition.getTaskType());
taskInstance.setTaskParams(taskDefinition.getTaskParams());
taskInstance.setWorkflowInstanceId(workflowInstance.getId());
taskInstance.setState(TaskExecutionStatus.SUBMITTED_SUCCESS);
taskInstance.setSubmitTime(new Date());
// ... 设置其他属性
```

**数据存储**:
- 任务实例保存到 `t_ds_task_instance` 表
- 状态：`SUBMITTED_SUCCESS`

---

## 阶段六：任务调度

### 6.1 调度循环

**位置**: `GlobalTaskDispatchWaitingQueueLooper`

Master 的后台线程循环从等待队列获取任务并调度：

```java
@Override
public void run() {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            // 从等待队列获取任务
            ITaskExecutionRunnable taskExecutionRunnable = 
                globalTaskDispatchWaitingQueue.takeTask();
            
            // 调度任务
            taskDispatcher.dispatchTask(taskExecutionRunnable);
        } catch (Exception e) {
            log.error("Task dispatch error", e);
        }
    }
}
```

### 6.2 选择 Worker

**位置**: `WorkerTaskDispatcher.getTaskInstanceDispatchHost()`

```java
@Override
protected Optional<Host> getTaskInstanceDispatchHost(
    ITaskExecutionRunnable taskExecutionRunnable) {
    
    String workerGroup = taskExecutionRunnable
        .getTaskExecutionContext()
        .getWorkerGroup();
    
    // 使用负载均衡器选择 Worker
    return workerLoadBalancer.select(workerGroup).map(Host::of);
}
```

### 6.3 分发任务

**位置**: `WorkerTaskDispatcher.doDispatch()`

```java
@Override
protected void doDispatch(ITaskExecutionRunnable taskExecutionRunnable) {
    final TaskExecutionContext taskExecutionContext = 
        taskExecutionRunnable.getTaskExecutionContext();
    final String workerAddress = taskExecutionContext.getHost();
    
    try {
        // 通过 RPC 调用 Worker
        final TaskInstanceDispatchResponse response = Clients
            .withService(ITaskInstanceOperator.class)
            .withHost(workerAddress)
            .dispatchTask(new TaskInstanceDispatchRequest(taskExecutionContext));
        
        if (!response.isDispatchSuccess()) {
            throw new TaskDispatchException("Dispatch task failed");
        }
    } catch (Exception e) {
        throw new TaskDispatchException("Dispatch task failed", e);
    }
}
```

### 6.4 更新任务状态

任务分发成功后：
- 任务实例状态：`SUBMITTED_SUCCESS` → `RUNNING_EXEUTION`
- 设置 Worker 地址：`taskInstance.setHost(workerAddress)`
- 设置开始时间：`taskInstance.setStartTime(new Date())`

---

## 阶段七：Worker 接收任务

### 7.1 RPC 接收

**位置**: `TaskInstanceDispatchOperationFunction.operate()`

```java
@Override
public TaskInstanceDispatchResponse operate(
    TaskInstanceDispatchRequest request) {
    
    TaskExecutionContext taskExecutionContext = request.getTaskExecutionContext();
    
    // 1. 检查 Worker 状态
    if (!ServerLifeCycleManager.isRunning()) {
        return TaskInstanceDispatchResponse.failed(
            taskInstanceId, "server is not running");
    }
    
    // 2. 设置日志路径
    taskExecutionContext.setLogPath(
        LogUtils.getTaskInstanceLogFullPath(taskExecutionContext));
    
    // 3. 创建任务执行器
    WorkerTaskExecutor workerTaskExecutor = workerTaskExecutorFactoryBuilder
        .createWorkerTaskExecutorFactory(taskExecutionContext)
        .createWorkerTaskExecutor();
    
    // 4. 提交到线程池执行
    if (workerTaskExecutorThreadPool.submitWorkerTaskExecutor(workerTaskExecutor)) {
        return TaskInstanceDispatchResponse.success(taskInstanceId);
    } else {
        return TaskInstanceDispatchResponse.failed(
            taskInstanceId, "Thread pool is full");
    }
}
```

### 7.2 发送任务已分发事件

**位置**: `WorkerTaskExecutor.initializeTask()`

```java
// 发送任务已分发事件，通知 Master
workerMessageSender.sendMessageWithRetry(
    taskExecutionContext,
    ITaskExecutionEvent.TaskInstanceExecutionEventType.DISPATCHED);
```

---

## 阶段八：Worker 执行任务

### 8.1 任务执行

**位置**: `WorkerTaskExecutor.run()`

```java
@Override
public void run() {
    try {
        // 1. 初始化任务
        initializeTask();
        
        // 2. 执行前处理
        beforeExecute();
        
        // 3. 执行任务（调用 SqlTask.handle()）
        executeTask(taskCallBack);
        
        // 4. 执行后处理
        afterExecute();
        
        // 5. 关闭日志
        closeLogAppender();
    } catch (Throwable ex) {
        afterThrowing(ex);
    }
}
```

### 8.2 发送执行事件

任务执行过程中会发送多个事件：

1. **RUNNING 事件**: 任务开始执行时
2. **SUCCESS/FAILURE 事件**: 任务执行完成时

```java
// 发送任务运行中事件
workerMessageSender.sendMessageWithRetry(
    taskExecutionContext,
    ITaskExecutionEvent.TaskInstanceExecutionEventType.RUNNING);

// 任务执行完成后
workerMessageSender.sendMessageWithRetry(
    taskExecutionContext,
    ITaskExecutionEvent.TaskInstanceExecutionEventType.SUCCESS);
```

---

## 阶段九：Master 接收事件并更新状态

### 9.1 事件监听器

**位置**: `TaskExecutionEventListenerImpl`

```java
@Override
public void onTaskInstanceExecutionSuccess(TaskExecutionSuccessEvent event) {
    // 1. 获取任务执行 Runnable
    final ITaskExecutionRunnable taskExecutionRunnable = 
        getTaskExecutionRunnable(event);
    
    // 2. 发布生命周期事件
    final TaskSuccessLifecycleEvent lifecycleEvent = TaskSuccessLifecycleEvent.builder()
        .taskExecutionRunnable(taskExecutionRunnable)
        .endTime(new Date(event.getEndTime()))
        .varPool(event.getVarPool())
        .build();
    
    taskExecutionRunnable.getWorkflowEventBus().publish(lifecycleEvent);
    
    // 3. 发送 ACK 确认
    Clients
        .withService(ITaskInstanceExecutionEventAckListener.class)
        .withHost(event.getTaskInstanceHost())
        .handleTaskInstanceExecutionSuccessEventAck(
            TaskInstanceExecutionSuccessEventAck.success(event.getTaskInstanceId()));
}
```

### 9.2 更新任务状态

**位置**: `TaskSuccessLifecycleEventHandler`

```java
// 更新任务实例状态
taskInstance.setState(TaskExecutionStatus.SUCCESS);
taskInstance.setEndTime(new Date());
taskInstanceDao.updateById(taskInstance);
```

### 9.3 继续后续任务

如果任务执行成功，工作流引擎会：
1. 检查后续任务是否满足执行条件
2. 如果满足，创建并提交后续任务实例
3. 重复阶段五到阶段九的流程

---

## 完整调用链

### API 到 Master

```
ExecutorController.triggerWorkflowDefinition()
  ↓
ExecutorServiceImpl.triggerWorkflowDefinition()
  ↓
TriggerWorkflowExecutorDelegate.execute()
  ↓
Clients.withService(IWorkflowControlClient.class)
  .withHost(masterAddress)
  .manualTriggerWorkflow(request)
  ↓ (Netty RPC)
MasterRpcServer (接收 RPC 请求)
  ↓
WorkflowControlClient.manualTriggerWorkflow()
  ↓
WorkflowManualTrigger.triggerWorkflow()
  ↓
  1. constructWorkflowInstance() → 保存到 t_ds_process_instance
  2. constructTriggerCommand() → 保存到 t_ds_command
  ↓
返回 WorkflowManualTriggerResponse (包含 workflowInstanceId)
```

### Master 处理 Command

```
CommandEngine.run() (后台循环线程)
  ↓
commandFetcher.fetchCommands() (从 t_ds_command 表获取)
  ↓
WorkflowExecutionRunnableFactory.createWorkflowExecuteRunnable(command)
  ↓
  1. deleteCommandOrThrow() (删除 Command，防止重复处理)
  2. RunWorkflowCommandHandler.handleCommand()
     - assembleWorkflowDefinition()
     - assembleProject()
     - assembleWorkflowGraph()
     - assembleWorkflowInstance() (更新状态为 RUNNING_EXECUTION)
     - assembleWorkflowExecutionGraph() (创建 TaskExecutionRunnable)
  ↓
创建 WorkflowExecutionRunnable
  ↓
workflowRepository.put(workflowExecutionRunnable)
  ↓
发布 WorkflowStartLifecycleEvent
  ↓
TaskStartLifecycleEventHandler 处理
  ↓
  1. initializeFirstRunTaskInstance() (创建任务实例)
  2. 保存到 t_ds_task_instance
  3. 提交到 globalTaskDispatchWaitingQueue
```

### Master 调度到 Worker

```
GlobalTaskDispatchWaitingQueueLooper.run() (后台循环线程)
  ↓
globalTaskDispatchWaitingQueue.takeTask()
  ↓
WorkerTaskDispatcher.dispatchTask()
  ↓
  1. workerLoadBalancer.select() (选择 Worker)
  2. 更新任务状态为 RUNNING_EXEUTION
  3. 设置 Worker 地址
  ↓
Clients.withService(ITaskInstanceOperator.class)
  .withHost(workerAddress)
  .dispatchTask(request)
  ↓ (Netty RPC)
WorkerRpcServer (接收 RPC 请求)
  ↓
TaskInstanceDispatchOperationFunction.operate()
  ↓
  1. 创建 WorkerTaskExecutor
  2. 提交到 workerTaskExecutorThreadPool
  ↓
返回 TaskInstanceDispatchResponse.success()
```

### Worker 执行任务

```
WorkerTaskExecutor.run() (线程池执行)
  ↓
  1. initializeTask()
  2. beforeExecute()
  3. executeTask()
     - SqlTaskChannel.createTask() → 创建 SqlTask
     - SqlTask.handle() → 执行 SQL
  4. afterExecute()
  ↓
发送事件到 Master:
  - DISPATCHED (任务已分发)
  - RUNNING (任务运行中)
  - SUCCESS (任务成功)
```

### Master 接收事件

```
TaskExecutionEventListenerImpl (Master RPC Server 接收事件)
  ↓
onTaskInstanceExecutionSuccess()
  ↓
  1. 发布 TaskSuccessLifecycleEvent
  2. 更新任务实例状态为 SUCCESS
  3. 发送 ACK 确认
  ↓
检查后续任务
  ↓
如果满足条件，创建后续任务实例，重复调度流程
```

---

## 关键数据表

### 1. t_ds_process_instance (工作流实例表)

**关键字段**:
- `id`: 工作流实例 ID
- `process_definition_code`: 工作流定义编码
- `state`: 状态（SUBMITTED_SUCCESS → RUNNING_EXECUTION → SUCCESS/FAILURE）
- `host`: Master 地址
- `start_time`: 开始时间

### 2. t_ds_command (命令表)

**关键字段**:
- `id`: Command ID
- `command_type`: 命令类型（START_PROCESS）
- `process_definition_code`: 工作流定义编码
- `process_instance_id`: 工作流实例 ID
- `command_param`: 命令参数（JSON）

### 3. t_ds_task_instance (任务实例表)

**关键字段**:
- `id`: 任务实例 ID
- `task_code`: 任务编码
- `process_instance_id`: 工作流实例 ID
- `state`: 状态（SUBMITTED_SUCCESS → RUNNING_EXEUTION → SUCCESS/FAILURE）
- `host`: Worker 地址
- `submit_time`: 提交时间
- `start_time`: 开始时间
- `end_time`: 结束时间

---

## 关键组件说明

### 1. CommandEngine

- **作用**: Master 的后台线程，循环从数据库获取 Command 并处理
- **频率**: 默认每秒检查一次
- **并发**: 使用线程池异步处理多个 Command

### 2. WorkflowExecutionRunnable

- **作用**: 表示一个正在执行的工作流实例
- **生命周期**: 从 Command 处理开始，到工作流执行完成
- **职责**: 管理工作流状态、任务依赖、事件发布

### 3. TaskExecutionRunnable

- **作用**: 表示一个任务节点
- **创建时机**: 在 `assembleWorkflowExecutionGraph()` 时创建
- **任务实例创建**: 在任务可以执行时，通过 `initializeFirstRunTaskInstance()` 创建

### 4. GlobalTaskDispatchWaitingQueue

- **作用**: 全局任务调度等待队列
- **生产者**: TaskStartLifecycleEventHandler（任务可以执行时）
- **消费者**: GlobalTaskDispatchWaitingQueueLooper（Master 调度线程）

### 5. WorkerTaskExecutor

- **作用**: Worker 端的任务执行器
- **创建**: 在 Worker 接收任务分发请求时创建
- **执行**: 在线程池中执行，调用具体的任务插件（如 SqlTask）

---

## 总结

手动执行任务的完整流程涉及多个组件和阶段：

1. **API 层**: 接收请求，通过 RPC 调用 Master
2. **Master 触发层**: 创建工作流实例和 Command
3. **Master 调度层**: CommandEngine 循环处理 Command，创建工作流执行 Runnable
4. **Master 任务层**: 创建任务实例，提交到调度队列
5. **Master 分发层**: 选择 Worker，通过 RPC 分发任务
6. **Worker 执行层**: 接收任务，执行具体任务逻辑
7. **事件反馈层**: Worker 通过事件通知 Master 任务状态

整个过程采用**事件驱动架构**和**异步处理**，通过 RPC 和事件机制实现 Master 和 Worker 之间的协调，确保任务可靠执行和状态同步。

