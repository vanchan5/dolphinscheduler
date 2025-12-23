# DolphinScheduler Master 工作流状态机详细解析

## 一、核心概念

### 1. 状态机（State Machine）模式

DolphinScheduler 使用状态机模式来管理工作流和任务的生命周期。状态机的核心思想是：
- **状态（State）**：工作流或任务在某个时刻的状态（如 RUNNING、SUCCESS、FAILURE）
- **事件（Event）**：触发状态转换的动作（如 START、FINISH、PAUSE）
- **动作（Action）**：在特定状态下接收到特定事件时执行的操作

### 2. ILifecycleEventHandler 接口

```java
public interface ILifecycleEventHandler<T extends AbstractLifecycleEvent> {
    // 处理事件的核心方法
    void handle(final IWorkflowExecutionRunnable workflowExecutionRunnable, final T event);
    
    // 返回该处理器匹配的事件类型
    ILifecycleEventType matchEventType();
}
```

**作用**：
- 作为事件处理器的基础接口
- 每个事件类型对应一个 Handler 实现
- 通过 `matchEventType()` 建立事件类型到处理器的映射关系

### 3. 事件处理的两层架构

#### 第一层：EventHandler（事件分发层）
- **WorkflowStartLifecycleEventHandler**：处理工作流启动事件
- **WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEventHandler**：处理任务完成后的拓扑转换事件
- **TaskStartLifecycleEventHandler**：处理任务启动事件
- **TaskSuccessLifecycleEventHandler**：处理任务成功事件
- 等等...

#### 第二层：StateAction（状态动作层）
- **WorkflowRunningStateAction**：工作流处于 RUNNING 状态时的动作
- **WorkflowSuccessStateAction**：工作流处于 SUCCESS 状态时的动作
- **TaskSubmittedStateAction**：任务处于 SUBMITTED 状态时的动作
- 等等...

**工作流程**：
```
Event → EventHandler → 根据当前状态选择 StateAction → 执行具体动作
```

## 二、工作流状态机详解

### 1. 工作流状态枚举

```java
public enum WorkflowExecutionStatus {
    SUBMITTED_SUCCESS(0, "submitted"),      // 已提交
    RUNNING_EXECUTION(1, "running"),       // 运行中
    READY_PAUSE(2, "ready pause"),          // 准备暂停
    PAUSE(3, "pause"),                     // 已暂停
    READY_STOP(4, "ready stop"),            // 准备停止
    STOP(5, "stop"),                        // 已停止
    FAILURE(6, "failure"),                  // 失败
    SUCCESS(7, "success"),                  // 成功
    SERIAL_WAIT(14, "serial wait"),         // 串行等待
    FAILOVER(18, "failover");               // 故障转移
}
```

### 2. 状态到 StateAction 的映射

通过 `WorkflowStateActionFactory` 建立映射：

```java
// 启动时，Spring 自动注入所有 IWorkflowStateAction 实现
public WorkflowStateActionFactory(List<IWorkflowStateAction> workflowStateActions) {
    workflowStateActions.forEach(
        workflowStateAction -> workflowStateActionMap.put(
            workflowStateAction.matchState(),  // 状态作为 Key
            workflowStateAction                // StateAction 作为 Value
        )
    );
}
```

**映射关系**：
- `RUNNING_EXECUTION` → `WorkflowRunningStateAction`
- `SUCCESS` → `WorkflowSuccessStateAction`
- `FAILURE` → `WorkflowFailedStateAction`
- `PAUSE` → `WorkflowPausedStateAction`
- 等等...

### 3. 事件类型

```java
public enum WorkflowLifecycleEventType implements ILifecycleEventType {
    START,                                    // 启动事件
    TOPOLOGY_LOGICAL_TRANSACTION_WITH_TASK_FINISH,  // 任务完成后的拓扑转换
    PAUSE,                                    // 暂停事件
    STOP,                                     // 停止事件
    SUCCEED,                                  // 成功事件
    FAILED,                                   // 失败事件
    FINALIZE;                                 // 最终化事件
}
```

## 三、完整调用流程详解

### 阶段1：工作流启动

#### 1.1 发布启动事件
```java
// CommandEngine.bootstrapWorkflowExecutionRunnable()
workflowExecutionRunnable.getWorkflowEventBus()
    .publish(WorkflowStartLifecycleEvent.of(workflowExecutionRunnable));
```

#### 1.2 事件总线分发
```java
// WorkflowEventBusFireWorker.doFireSingleEvent()
ILifecycleEventHandler handler = eventHandlerMap.get(event.getEventType());
// 根据事件类型获取：WorkflowStartLifecycleEventHandler
handler.handle(workflowExecutionRunnable, event);
```

#### 1.3 EventHandler 处理
```java
// WorkflowStartLifecycleEventHandler.handle()
@Override
public void handle(IWorkflowStateAction workflowStateAction, 
                   IWorkflowExecutionRunnable workflowExecutionRunnable, 
                   WorkflowStartLifecycleEvent event) {
    // 调用 StateAction 的 startEventAction
    workflowStateAction.startEventAction(workflowExecutionRunnable, event);
}
```

#### 1.4 根据状态选择 StateAction
```java
// AbstractWorkflowLifecycleEventHandler.handle()
IWorkflowStateAction action = workflowStateActionFactory.getAction(
    workflowExecutionRunnable.getState()  // 当前状态：RUNNING_EXECUTION
);
// 返回：WorkflowRunningStateAction
```

#### 1.5 StateAction 执行动作
```java
// WorkflowRunningStateAction.startEventAction()
@Override
public void startEventAction(IWorkflowExecutionRunnable workflowExecutionRunnable,
                            WorkflowStartLifecycleEvent event) {
    // 获取工作流执行图
    IWorkflowExecutionGraph graph = workflowExecutionRunnable.getWorkflowExecutionGraph();
    
    // 触发起始节点任务
    triggerTasks(workflowExecutionRunnable, graph.getStartNodes());
}
```

#### 1.6 触发任务
```java
// AbstractWorkflowStateAction.triggerTasks()
protected void triggerTasks(IWorkflowExecutionRunnable workflowExecutionRunnable,
                           List<ITaskExecutionRunnable> taskRunnables) {
    // 1. 过滤出满足触发条件的任务
    List<ITaskExecutionRunnable> readyTasks = taskRunnables.stream()
        .filter(graph::isTriggerConditionMet)  // 检查前置任务是否完成
        .collect(Collectors.toList());
    
    // 2. 标记任务为活跃状态
    for (ITaskExecutionRunnable task : readyTasks) {
        graph.markTaskExecutionRunnableActive(task);
        
        // 3. 发布任务启动事件
        workflowEventBus.publish(TaskStartLifecycleEvent.of(task));
    }
}
```

### 阶段2：任务启动处理

#### 2.1 任务启动事件处理
```java
// TaskStartLifecycleEventHandler.handle()
@Override
public void handle(IWorkflowExecutionRunnable workflowExecutionRunnable,
                  TaskStartLifecycleEvent event) {
    ITaskExecutionRunnable taskRunnable = event.getTaskExecutionRunnable();
    
    // 1. 初始化 TaskInstance（如果未初始化）
    if (!taskRunnable.isTaskInstanceInitialized()) {
        taskRunnable.initializeFirstRunTaskInstance();
        // → 创建 TaskInstance 并插入数据库
    }
    
    // 2. 根据任务状态选择 TaskStateAction
    TaskExecutionStatus state = taskRunnable.getTaskInstance().getState();
    ITaskStateAction taskStateAction = taskStateActionFactory.getTaskStateAction(state);
    // 状态为 SUBMITTED_SUCCESS → TaskSubmittedStateAction
    
    // 3. 调用 StateAction
    taskStateAction.startEventAction(workflowExecutionRunnable, taskRunnable, event);
}
```

#### 2.2 TaskSubmittedStateAction 处理
```java
// TaskSubmittedStateAction.startEventAction()
@Override
public void startEventAction(IWorkflowExecutionRunnable workflowExecutionRunnable,
                            ITaskExecutionRunnable taskRunnable,
                            TaskStartLifecycleEvent event) {
    // 检查工作流是否需要暂停或停止
    if (workflowExecutionRunnable.isWorkflowReadyPause()) {
        workflowEventBus.publish(TaskPausedLifecycleEvent.of(taskRunnable));
        return;
    }
    
    if (workflowExecutionRunnable.isWorkflowReadyStop()) {
        workflowEventBus.publish(TaskKilledLifecycleEvent.of(taskRunnable));
        return;
    }
    
    // 尝试分发任务
    tryToDispatchTask(taskRunnable);
}
```

#### 2.3 任务分发
```java
// AbstractTaskStateAction.tryToDispatchTask()
protected void tryToDispatchTask(ITaskExecutionRunnable taskRunnable) {
    // 检查是否需要任务组资源
    if (isTaskNeedAcquireTaskGroupSlot(taskRunnable)) {
        acquireTaskGroupSlot(taskRunnable);
        return;
    }
    
    // 发布任务分发事件
    taskRunnable.getWorkflowEventBus()
        .publish(TaskDispatchLifecycleEvent.of(taskRunnable));
}
```

#### 2.4 任务入队
```java
// TaskSubmittedStateAction.dispatchEventAction()
@Override
public void dispatchEventAction(...) {
    // 1. 计算延迟时间
    long remainTimeMills = DateUtils.getRemainTime(...);
    
    // 2. 更新任务状态
    taskInstance.setState(TaskExecutionStatus.SUBMITTED_SUCCESS);
    taskInstanceDao.updateById(taskInstance);
    
    // 3. 任务入队
    globalTaskDispatchWaitingQueue.dispatchTaskExecuteRunnableWithDelay(
        taskRunnable, remainTimeMills
    );
}
```

### 阶段3：任务执行

#### 3.1 任务分发到 Worker
```java
// GlobalTaskDispatchWaitingQueueLooper.doDispatch()
void doDispatch() {
    // 1. 从队列取出任务
    ITaskExecutionRunnable taskRunnable = globalTaskDispatchWaitingQueue.takeTaskExecuteRunnable();
    
    // 2. 检查任务状态
    TaskExecutionStatus status = taskRunnable.getTaskInstance().getState();
    if (status == SUBMITTED_SUCCESS || status == DELAY_EXECUTION) {
        // 3. 分发到 Worker
        taskExecutorClient.dispatch(taskRunnable);
    }
}
```

#### 3.2 Worker 执行任务
```java
// PhysicalTaskExecutorClientDelegator.dispatch()
@Override
public void dispatch(ITaskExecutionRunnable taskRunnable) {
    // 1. 负载均衡选择 Worker
    String workerHost = workerLoadBalancer.select(workerGroup);
    
    // 2. 更新任务实例的 host
    taskExecutionContext.setHost(workerHost);
    taskInstance.setHost(workerHost);
    taskInstanceDao.updateById(taskInstance);
    
    // 3. RPC 调用 Worker
    TaskExecutorDispatchResponse response = Clients
        .withService(IPhysicalTaskExecutorOperator.class)
        .withHost(workerHost)
        .dispatchTask(TaskExecutorDispatchRequest.of(taskExecutionContext));
}
```

### 阶段4：任务完成处理

#### 4.1 Worker 回调 Master
当任务执行完成时，Worker 通过 RPC 回调 Master，发送任务完成事件：
- `TaskSuccessLifecycleEvent`：任务成功
- `TaskFailedLifecycleEvent`：任务失败

#### 4.2 任务成功处理
```java
// TaskSuccessLifecycleEventHandler.handle()
@Override
public void handle(IWorkflowExecutionRunnable workflowExecutionRunnable,
                  TaskSuccessLifecycleEvent event) {
    ITaskExecutionRunnable taskRunnable = event.getTaskExecutionRunnable();
    TaskExecutionStatus state = taskRunnable.getTaskInstance().getState();
    
    // 根据状态选择 StateAction（如 TaskSuccessStateAction）
    ITaskStateAction taskStateAction = taskStateActionFactory.getTaskStateAction(state);
    taskStateAction.succeedEventAction(workflowExecutionRunnable, taskRunnable, event);
}
```

#### 4.3 TaskSuccessStateAction 处理
```java
// TaskSuccessStateAction.succeedEventAction()
@Override
public void succeedEventAction(...) {
    // 1. 更新任务状态为 SUCCESS
    taskInstance.setState(TaskExecutionStatus.SUCCESS);
    taskInstance.setEndTime(event.getEndTime());
    taskInstance.setVarPool(event.getVarPool());
    taskInstanceDao.updateById(taskInstance);
    
    // 2. 合并变量池到工作流
    mergeTaskVarPoolToWorkflow(workflowExecutionRunnable, taskRunnable);
    
    // 3. 发布工作流拓扑转换事件
    publishWorkflowInstanceTopologyLogicalTransitionEvent(taskRunnable);
}
```

#### 4.4 触发后续任务
```java
// AbstractTaskStateAction.publishWorkflowInstanceTopologyLogicalTransitionEvent()
protected void publishWorkflowInstanceTopologyLogicalTransitionEvent(
    ITaskExecutionRunnable taskRunnable) {
    
    // 1. 标记任务为非活跃状态
    workflowExecutionGraph.markTaskExecutionRunnableInActive(taskRunnable);
    
    // 2. 发布拓扑转换事件
    workflowEventBus.publish(
        WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent.of(
            workflowExecutionRunnable, taskRunnable
        )
    );
}
```

#### 4.5 拓扑转换事件处理
```java
// WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEventHandler.handle()
@Override
public void handle(IWorkflowStateAction workflowStateAction,
                  IWorkflowExecutionRunnable workflowExecutionRunnable,
                  WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent event) {
    
    // 调用 StateAction 的拓扑转换方法
    workflowStateAction.topologyLogicalTransitionEventAction(
        workflowExecutionRunnable, event
    );
}
```

#### 4.6 触发后续任务
```java
// WorkflowRunningStateAction.topologyLogicalTransitionEventAction()
@Override
public void topologyLogicalTransitionEventAction(...) {
    // 触发后续任务
    super.tryToTriggerSuccessorsAfterTaskFinish(
        workflowExecutionRunnable, 
        event.getTaskExecutionRunnable()
    );
}

// AbstractWorkflowStateAction.tryToTriggerSuccessorsAfterTaskFinish()
protected void tryToTriggerSuccessorsAfterTaskFinish(...) {
    IWorkflowExecutionGraph graph = workflowExecutionRunnable.getWorkflowExecutionGraph();
    
    // 1. 检查是否是任务链的末端
    if (graph.isEndOfTaskChain(taskRunnable)) {
        emitWorkflowFinishedEventIfApplicable(workflowExecutionRunnable);
        return;
    }
    
    // 2. 调整后续任务流
    successorFlowAdjuster.adjustSuccessorFlow(taskRunnable);
    
    // 3. 触发后续任务
    triggerTasks(workflowExecutionRunnable, graph.getSuccessors(taskRunnable));
    // → 回到阶段1.6，触发后续任务
}
```

### 阶段5：工作流完成

#### 5.1 检查工作流是否可以完成
```java
// AbstractWorkflowStateAction.emitWorkflowFinishedEventIfApplicable()
protected void emitWorkflowFinishedEventIfApplicable(
    IWorkflowExecutionRunnable workflowExecutionRunnable) {
    
    IWorkflowExecutionGraph graph = workflowExecutionRunnable.getWorkflowExecutionGraph();
    
    // 1. 检查是否所有任务都已完成
    if (!graph.isAllTaskExecutionRunnableChainFinish()) {
        return;  // 还有任务未完成，不触发完成事件
    }
    
    // 2. 检查是否有失败的任务
    if (graph.isExistFailureTaskExecutionRunnableChain()) {
        workflowEventBus.publish(WorkflowFailedLifecycleEvent.of(workflowExecutionRunnable));
        return;
    }
    
    // 3. 检查是否所有任务都成功
    if (graph.isAllTaskExecutionRunnableChainSuccess()) {
        workflowEventBus.publish(WorkflowSucceedLifecycleEvent.of(workflowExecutionRunnable));
        return;
    }
}
```

#### 5.2 工作流成功处理
```java
// WorkflowSucceedLifecycleEventHandler.handle()
@Override
public void handle(IWorkflowStateAction workflowStateAction,
                  IWorkflowExecutionRunnable workflowExecutionRunnable,
                  WorkflowSucceedLifecycleEvent event) {
    
    workflowStateAction.succeedEventAction(workflowExecutionRunnable, event);
}

// WorkflowRunningStateAction.succeedEventAction()
@Override
public void succeedEventAction(...) {
    // 1. 验证所有任务都成功
    if (!graph.isAllTaskExecutionRunnableChainSuccess()) {
        throw new IllegalStateException("存在未成功的任务链");
    }
    
    // 2. 完成工作流
    workflowFinish(workflowExecutionRunnable, WorkflowExecutionStatus.SUCCESS);
}
```

#### 5.3 工作流最终化
```java
// AbstractWorkflowStateAction.workflowFinish()
protected void workflowFinish(IWorkflowExecutionRunnable workflowExecutionRunnable,
                             WorkflowExecutionStatus status) {
    WorkflowInstance workflowInstance = workflowExecutionRunnable.getWorkflowInstance();
    
    // 1. 设置结束时间
    workflowInstance.setEndTime(new Date());
    
    // 2. 转换工作流状态
    transformWorkflowInstanceState(workflowExecutionRunnable, status);
    // → 更新数据库中的工作流状态为 SUCCESS
    
    // 3. 发布最终化事件
    workflowExecutionRunnable.getWorkflowEventBus()
        .publish(WorkflowFinalizeLifecycleEvent.of(workflowExecutionRunnable));
}
```

## 四、关键设计模式

### 1. 状态机模式
- **状态**：工作流和任务的状态
- **事件**：触发状态转换的动作
- **动作**：在特定状态下接收到特定事件时执行的操作

### 2. 事件驱动模式
- 所有状态转换都通过事件触发
- 事件总线负责事件的分发和处理
- 解耦了各个组件之间的依赖

### 3. 策略模式
- 不同的状态对应不同的 StateAction 实现
- 通过工厂模式根据状态选择对应的策略

### 4. 观察者模式
- EventHandler 监听特定类型的事件
- 当事件发布时，自动触发对应的处理器

## 五、完整流程图

```
1. 工作流启动
   ↓
   WorkflowStartLifecycleEvent
   ↓
   WorkflowStartLifecycleEventHandler
   ↓
   WorkflowStateActionFactory.getAction(RUNNING_EXECUTION)
   ↓
   WorkflowRunningStateAction.startEventAction()
   ↓
   triggerTasks() → 发布 TaskStartLifecycleEvent

2. 任务启动
   ↓
   TaskStartLifecycleEvent
   ↓
   TaskStartLifecycleEventHandler
   ↓
   TaskStateActionFactory.getAction(SUBMITTED_SUCCESS)
   ↓
   TaskSubmittedStateAction.startEventAction()
   ↓
   tryToDispatchTask() → 发布 TaskDispatchLifecycleEvent
   ↓
   任务入队 → GlobalTaskDispatchWaitingQueue

3. 任务执行
   ↓
   GlobalTaskDispatchWaitingQueueLooper
   ↓
   TaskExecutorClient.dispatch()
   ↓
   PhysicalTaskExecutorClientDelegator.dispatch()
   ↓
   Worker 执行任务

4. 任务完成
   ↓
   Worker 回调 → TaskSuccessLifecycleEvent
   ↓
   TaskSuccessLifecycleEventHandler
   ↓
   TaskSuccessStateAction.succeedEventAction()
   ↓
   publishWorkflowInstanceTopologyLogicalTransitionEvent()
   ↓
   WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent

5. 触发后续任务
   ↓
   WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEventHandler
   ↓
   WorkflowRunningStateAction.topologyLogicalTransitionEventAction()
   ↓
   tryToTriggerSuccessorsAfterTaskFinish()
   ↓
   triggerTasks() → 回到步骤1，触发后续任务

6. 工作流完成
   ↓
   所有任务完成 → emitWorkflowFinishedEventIfApplicable()
   ↓
   WorkflowSucceedLifecycleEvent
   ↓
   WorkflowSucceedLifecycleEventHandler
   ↓
   WorkflowRunningStateAction.succeedEventAction()
   ↓
   workflowFinish() → 更新状态为 SUCCESS
   ↓
   WorkflowFinalizeLifecycleEvent
```

## 六、总结

1. **事件驱动**：整个流程基于事件驱动，通过事件总线进行解耦
2. **状态机管理**：通过状态机模式管理复杂的状态转换逻辑
3. **分层设计**：EventHandler 负责事件分发，StateAction 负责具体业务逻辑
4. **自动触发**：任务完成后自动触发后续任务，形成完整的执行链
5. **容错机制**：通过状态检查确保工作流和任务状态的一致性
