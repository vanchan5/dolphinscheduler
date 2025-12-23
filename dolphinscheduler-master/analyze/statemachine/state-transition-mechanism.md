# DolphinScheduler 状态机状态变更机制详解

## 一、状态变更的核心机制

### 1. 状态变更不是自动的，而是由 StateAction 主动设置

**重要理解**：DolphinScheduler 的状态机**不是**传统意义上的自动状态机。状态变更不是通过状态转换表自动触发的，而是由 **StateAction 在特定事件处理时主动调用 `setState()` 方法**来变更状态。

### 2. 状态变更的两种方式

#### 方式1：直接设置状态（工作流初始状态）
在创建或初始化时直接设置状态，不通过事件触发。

#### 方式2：通过 StateAction 设置状态（运行时状态变更）
在事件处理过程中，StateAction 根据业务逻辑决定何时变更状态。

## 二、工作流状态变更详解

### 1. 工作流状态变更方法

```java
// AbstractWorkflowStateAction.transformWorkflowInstanceState()
protected void transformWorkflowInstanceState(
    IWorkflowExecutionRunnable workflowExecutionRunnable,
    WorkflowExecutionStatus targetState) {
    
    WorkflowInstance workflowInstance = workflowExecutionRunnable.getWorkflowInstance();
    WorkflowExecutionStatus originState = workflowInstance.getState();
    
    try {
        // 1. 更新内存中的状态
        workflowInstance.setState(targetState);
        
        // 2. 更新数据库中的状态
        workflowInstanceDao.updateById(workflowInstance);
        
        log.info("Success set WorkflowExecuteRunnable: {} state from: {} to {}",
            workflowInstance.getName(), originState.name(), targetState.name());
    } catch (Exception ex) {
        // 3. 如果更新失败，回滚内存状态
        workflowInstance.setState(originState);
        throw ex;
    }
}
```

**特点**：
- 同时更新内存和数据库
- 有异常回滚机制
- 记录状态变更日志

### 2. 工作流状态变更的时机

#### 2.1 初始状态设置（创建时）

```java
// RunWorkflowCommandHandler.assembleWorkflowInstance()
@Override
protected void assembleWorkflowInstance(WorkflowExecuteContextBuilder builder) {
    WorkflowInstance workflowInstance = workflowInstanceDao.queryById(command.getWorkflowInstanceId());
    
    // 直接设置状态为 RUNNING_EXECUTION
    workflowInstance.setStateWithDesc(
        WorkflowExecutionStatus.RUNNING_EXECUTION, 
        command.getCommandType().name()
    );
    
    workflowInstanceDao.upsertWorkflowInstance(workflowInstance);
}
```

**时机**：在 `CommandEngine` 处理 Command 时，创建 `WorkflowExecutionRunnable` 时设置。

#### 2.2 运行时状态变更（通过 StateAction）

**示例1：工作流暂停**

```java
// WorkflowRunningStateAction.pauseEventAction()
@Override
public void pauseEventAction(IWorkflowExecutionRunnable workflowExecutionRunnable,
                            WorkflowPauseLifecycleEvent event) {
    // 1. 变更工作流状态为 READY_PAUSE
    super.transformWorkflowInstanceState(
        workflowExecutionRunnable, 
        WorkflowExecutionStatus.READY_PAUSE
    );
    
    // 2. 暂停所有活跃任务
    super.pauseActiveTask(workflowExecutionRunnable);
}
```

**流程**：
1. 接收到 `WorkflowPauseLifecycleEvent` 事件
2. `WorkflowPauseLifecycleEventHandler` 处理事件
3. 根据当前状态（RUNNING_EXECUTION）选择 `WorkflowRunningStateAction`
4. 调用 `pauseEventAction()` 方法
5. 在方法内部调用 `transformWorkflowInstanceState()` 变更状态

**示例2：工作流完成**

```java
// AbstractWorkflowStateAction.workflowFinish()
protected void workflowFinish(IWorkflowExecutionRunnable workflowExecutionRunnable,
                             WorkflowExecutionStatus workflowExecutionStatus) {
    WorkflowInstance workflowInstance = workflowExecutionRunnable.getWorkflowInstance();
    
    // 1. 设置结束时间
    workflowInstance.setEndTime(new Date());
    
    // 2. 变更工作流状态（SUCCESS 或 FAILURE）
    transformWorkflowInstanceState(workflowExecutionRunnable, workflowExecutionStatus);
    
    // 3. 发布最终化事件
    workflowExecutionRunnable.getWorkflowEventBus()
        .publish(WorkflowFinalizeLifecycleEvent.of(workflowExecutionRunnable));
}
```

**流程**：
1. 所有任务完成后，触发 `WorkflowSucceedLifecycleEvent`
2. `WorkflowSucceedLifecycleEventHandler` 处理事件
3. `WorkflowRunningStateAction.succeedEventAction()` 被调用
4. 调用 `workflowFinish()` 方法
5. 在方法内部调用 `transformWorkflowInstanceState()` 变更状态为 SUCCESS

### 3. 工作流状态变更完整流程

```
事件触发
  ↓
EventHandler 处理
  ↓
根据当前状态选择 StateAction
  ↓
StateAction 的对应方法被调用
  ↓
在方法内部根据业务逻辑决定是否变更状态
  ↓
调用 transformWorkflowInstanceState() 或直接 setState()
  ↓
更新内存状态 + 更新数据库
```

## 三、任务状态变更详解

### 1. 任务状态变更方法

任务状态变更**没有统一的方法**，而是在各个 StateAction 中直接调用 `setState()` 然后更新数据库。

### 2. 任务状态变更的时机

#### 2.1 初始状态设置（创建时）

```java
// FirstRunTaskInstanceFactory.builder()
taskInstance.setState(TaskExecutionStatus.SUBMITTED_SUCCESS);
taskInstance.setFirstSubmitTime(new Date());
taskInstance.setSubmitTime(new Date());
// ... 其他属性设置
taskInstanceDao.insert(taskInstance);  // 插入数据库
```

**时机**：在 `TaskStartLifecycleEventHandler` 处理 `TaskStartLifecycleEvent` 时，如果任务未初始化，则创建 TaskInstance 并设置初始状态。

#### 2.2 运行时状态变更（通过 StateAction）

**示例1：任务延迟执行**

```java
// TaskSubmittedStateAction.dispatchEventAction()
@Override
public void dispatchEventAction(...) {
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    
    // 计算延迟时间
    long remainTimeMills = DateUtils.getRemainTime(...);
    
    if (remainTimeMills > 0) {
        // 变更状态为 DELAY_EXECUTION
        taskInstance.setState(TaskExecutionStatus.DELAY_EXECUTION);
        taskInstanceDao.updateById(taskInstance);
    }
    
    // 任务入队
    globalTaskDispatchWaitingQueue.dispatchTaskExecuteRunnableWithDelay(...);
}
```

**流程**：
1. 接收到 `TaskDispatchLifecycleEvent` 事件
2. `TaskDispatchLifecycleEventHandler` 处理事件
3. 根据当前状态（SUBMITTED_SUCCESS）选择 `TaskSubmittedStateAction`
4. 调用 `dispatchEventAction()` 方法
5. 在方法内部根据延迟时间决定是否变更状态

**示例2：任务分发到 Worker**

```java
// AbstractTaskStateAction.dispatchedEventAction()
@Override
public void dispatchedEventAction(...) {
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    
    // 变更状态为 DISPATCH
    taskInstance.setState(DISPATCH);
    taskInstance.setHost(taskDispatchedEvent.getExecutorHost());
    taskInstanceDao.updateById(taskInstance);
}
```

**流程**：
1. 任务成功分发到 Worker 后，触发 `TaskDispatchedLifecycleEvent`
2. `TaskDispatchedLifecycleEventHandler` 处理事件
3. 根据当前状态选择对应的 `TaskStateAction`
4. 调用 `dispatchedEventAction()` 方法
5. 在方法内部变更状态为 DISPATCH

**示例3：任务开始运行**

```java
// AbstractTaskStateAction.persistentTaskInstanceStartedEventToDB()
protected void persistentTaskInstanceStartedEventToDB(
    ITaskExecutionRunnable taskExecutionRunnable,
    TaskRunningLifecycleEvent taskRunningEvent) {
    
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    
    // 变更状态为 RUNNING_EXECUTION
    taskInstance.setState(TaskExecutionStatus.RUNNING_EXECUTION);
    taskInstance.setStartTime(taskRunningEvent.getStartTime());
    taskInstance.setLogPath(taskRunningEvent.getLogPath());
    taskInstanceDao.updateById(taskInstance);
}
```

**流程**：
1. Worker 开始执行任务后，通过 RPC 回调 Master，发送 `TaskRunningLifecycleEvent`
2. `TaskRunningLifecycleEventHandler` 处理事件
3. 根据当前状态（DISPATCH）选择 `TaskDispatchStateAction`
4. 调用 `startedEventAction()` 方法
5. 在方法内部调用 `persistentTaskInstanceStartedEventToDB()` 变更状态为 RUNNING_EXECUTION

**示例4：任务成功完成**

```java
// AbstractTaskStateAction.persistentTaskInstanceSuccessEventToDB()
protected void persistentTaskInstanceSuccessEventToDB(
    ITaskExecutionRunnable taskExecutionRunnable,
    TaskSuccessLifecycleEvent taskSuccessEvent) {
    
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    
    // 变更状态为 SUCCESS
    taskInstance.setState(TaskExecutionStatus.SUCCESS);
    taskInstance.setEndTime(taskSuccessEvent.getEndTime());
    taskInstance.setVarPool(taskSuccessEvent.getVarPool());
    taskInstanceDao.updateById(taskInstance);
}
```

**流程**：
1. Worker 任务执行成功后，通过 RPC 回调 Master，发送 `TaskSuccessLifecycleEvent`
2. `TaskSuccessLifecycleEventHandler` 处理事件
3. 根据当前状态（RUNNING_EXECUTION）选择 `TaskRunningStateAction`
4. 调用 `succeedEventAction()` 方法
5. 在方法内部调用 `persistentTaskInstanceSuccessEventToDB()` 变更状态为 SUCCESS

**示例5：任务失败**

```java
// AbstractTaskStateAction.persistentTaskInstanceFailedEventToDB()
private void persistentTaskInstanceFailedEventToDB(
    ITaskExecutionRunnable taskExecutionRunnable,
    TaskFailedLifecycleEvent taskFailedEvent) {
    
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    
    // 变更状态为 FAILURE
    taskInstance.setState(TaskExecutionStatus.FAILURE);
    taskInstance.setEndTime(taskFailedEvent.getEndTime());
    taskInstanceDao.updateById(taskInstance);
}
```

### 3. 任务状态变更完整流程

```
事件触发（来自 Worker 或内部）
  ↓
EventHandler 处理
  ↓
根据当前状态选择 TaskStateAction
  ↓
TaskStateAction 的对应方法被调用
  ↓
在方法内部直接调用 taskInstance.setState()
  ↓
调用 taskInstanceDao.updateById() 更新数据库
```

## 四、状态变更的关键点

### 1. 状态变更的触发条件

状态变更**不是自动的**，需要满足以下条件：

1. **有事件触发**：必须有对应的事件（LifecycleEvent）被发布
2. **EventHandler 处理**：事件被对应的 EventHandler 处理
3. **StateAction 执行**：根据当前状态选择对应的 StateAction
4. **业务逻辑判断**：StateAction 根据业务逻辑决定是否变更状态

### 2. 状态变更的一致性

- **内存和数据库同步**：状态变更时，同时更新内存中的对象和数据库
- **异常回滚**：如果数据库更新失败，会回滚内存状态
- **状态验证**：在 StateAction 方法中，通常会先验证当前状态是否匹配

### 3. 状态变更的时机

| 状态类型 | 变更时机 | 变更方式 |
|---------|---------|---------|
| 工作流初始状态 | 创建 WorkflowExecutionRunnable 时 | 直接 setState() |
| 工作流运行时状态 | 处理特定事件时 | transformWorkflowInstanceState() |
| 任务初始状态 | 创建 TaskInstance 时 | 直接 setState() |
| 任务运行时状态 | 处理特定事件时 | 直接 setState() + updateById() |

### 4. 状态变更的验证

在 StateAction 的方法中，通常会先验证当前状态：

```java
// AbstractTaskStateAction.throwExceptionIfStateIsNotMatch()
protected void throwExceptionIfStateIsNotMatch(ITaskExecutionRunnable taskExecutionRunnable) {
    TaskInstance taskInstance = taskExecutionRunnable.getTaskInstance();
    TaskExecutionStatus actualState = taskInstance.getState();
    TaskExecutionStatus expectState = matchState();  // StateAction 期望的状态
    
    if (actualState != expectState) {
        throw new IllegalStateException(
            "The task: " + taskInstance.getName() + 
            " state: " + actualState + " is not match: " + expectState
        );
    }
}
```

这确保了状态变更的**安全性**，防止在错误的状态下执行操作。

## 五、状态变更示例流程

### 示例：任务从 SUBMITTED_SUCCESS 到 SUCCESS 的完整流程

```
1. 任务创建
   TaskStartLifecycleEvent
   ↓
   TaskStartLifecycleEventHandler
   ↓
   FirstRunTaskInstanceFactory
   ↓
   taskInstance.setState(SUBMITTED_SUCCESS)
   taskInstanceDao.insert(taskInstance)

2. 任务分发
   TaskDispatchLifecycleEvent
   ↓
   TaskDispatchLifecycleEventHandler
   ↓
   TaskSubmittedStateAction.dispatchEventAction()
   ↓
   (状态保持 SUBMITTED_SUCCESS，或变更为 DELAY_EXECUTION)

3. 任务分发到 Worker
   TaskDispatchedLifecycleEvent
   ↓
   TaskDispatchedLifecycleEventHandler
   ↓
   AbstractTaskStateAction.dispatchedEventAction()
   ↓
   taskInstance.setState(DISPATCH)
   taskInstanceDao.updateById(taskInstance)

4. Worker 开始执行
   TaskRunningLifecycleEvent (Worker 回调)
   ↓
   TaskRunningLifecycleEventHandler
   ↓
   TaskDispatchStateAction.startedEventAction()
   ↓
   taskInstance.setState(RUNNING_EXECUTION)
   taskInstanceDao.updateById(taskInstance)

5. Worker 执行完成
   TaskSuccessLifecycleEvent (Worker 回调)
   ↓
   TaskSuccessLifecycleEventHandler
   ↓
   TaskRunningStateAction.succeedEventAction()
   ↓
   taskInstance.setState(SUCCESS)
   taskInstanceDao.updateById(taskInstance)
```

## 六、总结

### 关键理解

1. **状态变更不是自动的**：不是通过状态转换表自动触发，而是由 StateAction 主动设置
2. **事件驱动**：所有状态变更都通过事件触发，但事件本身不直接变更状态
3. **StateAction 决定**：StateAction 根据业务逻辑决定何时、如何变更状态
4. **同步更新**：状态变更时，同时更新内存和数据库，保证一致性
5. **状态验证**：在变更前会验证当前状态是否匹配，确保安全性

### 状态变更模式

```
事件 → EventHandler → StateAction → setState() → 更新数据库
```

这种设计的好处：
- **灵活性**：StateAction 可以根据业务逻辑灵活决定状态变更
- **可扩展性**：新增状态或状态转换时，只需添加新的 StateAction
- **可维护性**：状态变更逻辑集中在 StateAction 中，易于维护
- **可测试性**：每个 StateAction 可以独立测试
