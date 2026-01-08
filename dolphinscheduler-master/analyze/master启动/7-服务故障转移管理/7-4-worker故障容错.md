# Worker 故障容错详细分析

## 1. 概述

Worker 故障容错是 DolphinScheduler 高可用性的核心机制之一。当 Worker 节点故障时，Master 能够自动检测故障、转移任务并重新分发，确保任务的最终一致性。

### 1.1 核心设计原则

1. **关注点分离**：`ClusterStateMonitors` 仅负责监控与事件发布，不直接处理故障转移
2. **延迟机制**：30 秒延迟避免误触发（网络抖动导致临时断开）
3. **事件驱动**：使用 `SystemEventBus` 解耦监控与处理
4. **内存隔离**：每个 Master 只处理自己内存中的任务，通过内存隔离实现并发控制，而非分布式锁

### 1.2 故障容错流程概览

```
Worker 故障检测 → 延迟确认（30秒）→ 发布 WorkerFailoverEvent → 
查询需要故障转移的任务 → 尝试接管任务 → 创建故障转移任务实例 → 
重新分发任务 → 任务继续执行
```

## 2. 完整时序图

```mermaid
sequenceDiagram
    participant ZK as ZooKeeper<br/>注册中心
    participant Registry as RegistryClient<br/>注册客户端
    participant WCM as WorkerClusters<br/>Worker集群管理器
    participant CSM as ClusterStateMonitors<br/>集群状态监控器
    participant SEB as SystemEventBus<br/>系统事件总线
    participant WFEH as WorkerFailoverEventHandler<br/>Worker故障转移事件处理器
    participant FC as FailoverCoordinator<br/>故障转移协调器
    participant TF as TaskFailover<br/>任务故障转移
    participant WER as WorkflowEventBus<br/>工作流事件总线
    participant TFLEH as TaskFailoverLifecycleEventHandler<br/>任务故障转移生命周期事件处理器
    participant TRSA as TaskRunningStateAction<br/>任务运行状态动作
    participant TER as TaskExecutionRunnable<br/>任务执行Runnable
    participant TEC as TaskExecutorClient<br/>任务执行器客户端
    participant PECD as PhysicalTaskExecutorClientDelegator<br/>物理任务执行器客户端委托器
    participant Worker1 as Worker1（故障）
    participant FTIF as FailoverTaskInstanceFactory<br/>故障转移任务实例工厂
    participant DB as 数据库
    participant Worker2 as Worker2（新）

    Note over ZK: Worker1 节点移除
    ZK->>Registry: 触发 TreeCacheEvent（REMOVE）
    Registry->>WCM: AbstractClusterSubscribeListener.notify(REMOVE)
    WCM->>WCM: onServerRemove(WorkerServerMetadata)
    WCM->>CSM: listener.onServerRemove(workerServer)
    
    Note over CSM: 延迟30秒避免误触发
    CSM->>SEB: publish(WorkerFailoverEvent, delay=30s)
    
    Note over SEB: 30秒后事件到期
    SEB->>WFEH: WorkerFailoverEvent（延迟事件）
    WFEH->>FC: failoverWorker(WorkerFailoverEvent)
    
    Note over FC: 检查 Worker 是否仍然存活
    FC->>WCM: getServer(workerAddress)
    WCM-->>FC: Optional<WorkerServerMetadata>
    
    alt Worker 存活且启动时间相同
        Note over FC: 误报，跳过故障转移
        FC->>FC: return
    else Worker 不存活或启动时间不同
        FC->>FC: doWorkerFailover(workerAddress, deadline, path)
        FC->>FC: getFailoverTaskForWorker(workerAddress, deadline)
        Note over FC: 筛选条件：
        Note over FC: 1. taskInstance.host == workerAddress
        Note over FC: 2. state == DISPATCH || RUNNING_EXECUTION
        Note over FC: 3. submitTime < deadline
        FC-->>FC: List<ITaskExecutionRunnable>
        
        loop 对每个需要故障转移的任务
            FC->>TF: failoverTask(taskExecutionRunnable)
            TF->>WER: publish(TaskFailoverLifecycleEvent)
            WER->>TFLEH: handle(TaskFailoverLifecycleEvent)
            TFLEH->>TRSA: failoverEventAction()
            TRSA->>TRSA: failoverTask(taskExecutionRunnable)
            TRSA->>TER: failover()
            
            Note over TER: 尝试接管任务
            TER->>TEC: reassignWorkflowInstanceHost()
            TEC->>PECD: reassignMasterHost()
            PECD->>Worker1: RPC reassignWorkflowInstanceHost()
            
            alt Worker1 可达且接管成功
                Worker1-->>PECD: TaskExecutorReassignMasterResponse(success=true)
                PECD-->>TER: return true
                TER->>TER: 接管成功，无需重建任务实例
                Note over TER: 任务仍在原 Worker 上运行，<br/>只需更新 workflowHost 指向新的 Master
            else Worker1 不可达或接管失败
                Worker1-->>PECD: RPC 调用失败或返回失败
                PECD-->>TER: return false
                
                Note over TER: 接管失败，创建新的故障转移任务实例
                TER->>FTIF: createTaskInstance()
                FTIF->>DB: 1. 克隆原任务实例<br/>2. 插入新任务实例（state=SUBMITTED_SUCCESS, host=null）
                FTIF->>DB: 3. 更新原任务实例（state=NEED_FAULT_TOLERANCE, flag=NO）
                FTIF->>FC: 释放任务组资源（如果有）
                FTIF-->>TER: 新任务实例
                
                TER->>TER: initializeTaskExecutionContext()
                TER->>WER: publish(TaskStartLifecycleEvent)
                
                Note over WER: 任务重新分发
                WER->>PECD: dispatchTask()（新任务实例）
                PECD->>Worker2: RPC dispatchTask()
                Worker2->>Worker2: 开始执行任务
            end
        end
        
        FC->>ZK: 持久化故障转移状态
        FC->>FC: 记录处理时间
    end
```

## 3. 完整流程详细说明

根据 `ClusterStateMonitors.workerRemoved()` 方法的注释，完整流程如下：

### 3.1 故障检测阶段

**步骤1：注册中心检测节点移除**
- 位置：[`ZookeeperTreeCacheListenerAdapter#childEvent(CuratorFramework, TreeCacheEvent)`](../../../../dolphinscheduler-plugin-registry/dolphinscheduler-registry-zookeeper/src/main/java/org/apache/dolphinscheduler/plugin/registry/zookeeper/ZookeeperTreeCacheListenerAdapter.java)
- 说明：ZooKeeper 的 TreeCache 监听器检测到 Worker 节点从注册中心移除

**步骤2：RegistryClient 触发订阅事件**
- 位置：[`AbstractClusterSubscribeListener#notify(Event)`](../../../../dolphinscheduler-plugin-registry/dolphinscheduler-registry-api/src/main/java/org/apache/dolphinscheduler/plugin/registry/api/listener/AbstractClusterSubscribeListener.java)
- 说明：将 ZooKeeper 事件转换为 DolphinScheduler 内部事件

**步骤3：WorkerClusters.onServerRemove() 被调用**
- 位置：[`WorkerClusters#onServerRemove(WorkerServerMetadata)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/WorkerClusters.java)
- 说明：Worker 集群管理器处理服务器移除事件

**步骤4：遍历所有监听器，调用 listener.onServerRemove(workerServer)**
- 说明：通知所有注册的监听器 Worker 已被移除

**步骤5：ClusterStateMonitors.workerRemoved() 被触发**
- 位置：[`ClusterStateMonitors#workerRemoved(WorkerServerMetadata)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171)
- 说明：集群状态监控器收到 Worker 移除事件

### 3.2 延迟确认阶段（30秒）

**步骤6：发布 WorkerFailoverEvent（延迟30秒）**
- 位置：[`ClusterStateMonitors#workerRemoved()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)
- 说明：发布延迟事件，延迟时间为 30 秒。如果 Worker 在 30 秒内重新连接，事件将被取消
- 代码：
  ```java
  systemEventBus.publish(WorkerFailoverEvent.of(workerServer, new Date(), 30_000));
  ```

### 3.3 故障转移处理阶段

**步骤7：WorkerFailoverEventHandler.handle()**
- 位置：[`WorkerFailoverEventHandler#handle(WorkerFailoverEvent)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEventHandler.java#L35-L36)
- 说明：事件处理器收到 Worker 故障转移事件，调用故障转移协调器

**步骤8：FailoverCoordinator.failoverWorker()**
- 位置：[`FailoverCoordinator#failoverWorker(WorkerFailoverEvent)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L330-L355)
- 说明：检查 Worker 是否仍然存活（可能重新连接到了注册中心）
  - 如果 Worker 存活且启动时间相同，说明是误报，跳过故障转移
  - 如果 Worker 不存活或启动时间不同，执行故障转移

**步骤9：FailoverCoordinator.doWorkerFailover()**
- 位置：[`FailoverCoordinator#doWorkerFailover(String, long, String)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L370-L401)
- 说明：执行 Worker 故障转移的核心逻辑

**步骤10：获取该 Worker 上的任务列表 (getFailoverTaskForWorker)**
- 位置：[`FailoverCoordinator#getFailoverTaskForWorker(String, Date)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L420-L457)
- 筛选条件：
  1. 任务实例已初始化
  2. 任务的主机地址匹配指定的 Worker 地址
  3. 任务状态为 `DISPATCH`（已分派）或 `RUNNING_EXECUTION`（正在执行）
  4. 任务的提交时间在故障转移截止时间之前

**步骤11：TaskFailover.failoverTask() - 对每个任务**
- 位置：[`TaskFailover#failoverTask(ITaskExecutionRunnable)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/TaskFailover.java#L29-L33)
- 说明：对每个需要故障转移的任务执行故障转移

**步骤12：发布 TaskFailoverLifecycleEvent**
- 位置：[`TaskFailover#failoverTask()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/TaskFailover.java#L31)
- 说明：发布任务故障转移生命周期事件

**步骤13：TaskFailoverLifecycleEventHandler.handle()**
- 位置：[`TaskFailoverLifecycleEventHandler#handle()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailoverLifecycleEventHandler.java#L32-L37)
- 说明：处理任务故障转移事件，根据任务状态调用对应的 StateAction

### 3.4 任务状态处理阶段（只处理状态为 DISPATCH 或 RUNNING_EXECUTION 的任务）

**步骤14：TaskRunningStateAction.failoverEventAction()**
- 位置：[`TaskRunningStateAction#failoverEventAction()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/TaskRunningStateAction.java#L133-L139)
- 说明：任务状态为 `RUNNING_EXECUTION` 时，调用故障转移处理
- 注意：只有状态为 `DISPATCH` 或 `RUNNING_EXECUTION` 的任务才会被处理，已完成或失败的任务不需要转移

**步骤15：AbstractTaskStateAction.failoverTask()**
- 位置：[`AbstractTaskStateAction#failoverTask(ITaskExecutionRunnable)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/AbstractTaskStateAction.java#L225-L227)
- 说明：抽象任务状态动作的故障转移方法

**步骤16：TaskExecutionRunnable.failover()**
- 位置：[`TaskExecutionRunnable#failover()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L294-L334)
- 说明：任务执行 Runnable 的故障转移方法

### 3.5 任务接管尝试阶段

**步骤17：takeOverTaskFromExecutor() - 最终调用这里！**
- 位置：[`TaskExecutionRunnable#takeOverTaskFromExecutor()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L410-L422)
- 说明：尝试从执行器接管任务（重新分配工作流实例主机）
- 这是一个试探性操作，用于确认 Worker 是否真的故障

**步骤18：TaskExecutorClient.reassignWorkflowInstanceHost()**
- 位置：[`TaskExecutorClient#reassignWorkflowInstanceHost(ITaskExecutionRunnable)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/TaskExecutorClient.java#L64-L73)
- 说明：重新分配工作流实例主机

**步骤19：PhysicalTaskExecutorClientDelegator.reassignMasterHost()**
- 位置：[`PhysicalTaskExecutorClientDelegator#reassignMasterHost(ITaskExecutionRunnable)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L107-L159)
- 说明：通过 RPC 调用 Worker，尝试重新分配 Master 主机地址
- 返回值：
  - **true**：Worker 可达且接管成功，任务仍在原 Worker 上运行，只需更新 workflowHost 指向新的 Master
  - **false**：Worker 不可达或接管失败，需要创建新的故障转移任务实例

### 3.6 故障转移任务实例创建阶段

**步骤20：返回 false，没有 Worker 接管，生成容错恢复任务实例**
- 位置：[`TaskExecutionRunnable#failover()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L303-L306)
- 说明：如果接管失败（`takeOverTaskFromExecutor()` 返回 false），需要创建新的故障转移任务实例

**步骤21：FailoverTaskInstanceFactory.createTaskInstance()**
- 位置：[`FailoverTaskInstanceFactory#createTaskInstance(FailoverTaskInstanceBuilder)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L47-L69)
- 处理逻辑：
  1. 克隆原任务实例
  2. **新任务实例**：
     - `id = null`（新实例）
     - `state = SUBMITTED_SUCCESS`
     - `host = null`
     - `varPool = null`
     - `logPath = null`
     - `executePath = null`
  3. **原任务实例**：
     - `state = NEED_FAULT_TOLERANCE`
     - `flag = NO`
  4. 释放任务组资源（如果有）

### 3.7 任务重新执行阶段

**步骤22：重新初始化 TaskExecutionContext**
- 位置：[`TaskExecutionRunnable#initializeTaskExecutionContext()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L399-L414)
- 说明：因为 taskInstance 已变化，需要重新初始化任务执行上下文

**步骤23：发布 TaskStartLifecycleEvent**
- 位置：[`TaskExecutionRunnable#failover()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L333)
- 说明：发布任务启动生命周期事件，触发任务重新执行

**步骤24：任务重新分发**
- 位置：[`PhysicalTaskExecutorClientDelegator#dispatch(ITaskExecutionRunnable)`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L65-L96)
- 说明：通过负载均衡器选择新的 Worker，重新分发任务
- 注意：
  - 不是其他 Worker 主动"接管"，而是 Master 重新分派任务到其他 Worker
  - 故障 Worker 会被自动排除（从注册中心移除或状态非 NORMAL）
  - 通过负载均衡器从正常 Worker 中选择一个进行分派
  - 新任务实例的 host 字段会被更新为选中的 Worker 地址

## 4. 完整流程图

```mermaid
flowchart TD
    Start([Worker 节点故障]) --> ZK[ZooKeeper 检测节点移除]
    ZK --> Registry[RegistryClient 触发订阅事件]
    Registry --> WCM[WorkerClusters.onServerRemove]
    WCM --> CSM[ClusterStateMonitors.workerRemoved]
    
    CSM --> Delay{延迟30秒}
    Delay -->|Worker 重新连接| Cancel([取消故障转移])
    Delay -->|Worker 未重新连接| Publish[发布 WorkerFailoverEvent]
    
    Publish --> WFEH[WorkerFailoverEventHandler.handle]
    WFEH --> FC[FailoverCoordinator.failoverWorker]
    
    FC --> Check{检查 Worker 是否存活}
    Check -->|存活且启动时间相同| Skip([跳过故障转移])
    Check -->|不存活或启动时间不同| Query[查询需要故障转移的任务]
    
    Query --> Filter[筛选任务条件<br/>1. host == workerAddress<br/>2. state == DISPATCH 或 RUNNING_EXECUTION<br/>3. submitTime < deadline]
    
    Filter --> Loop{遍历每个任务}
    Loop --> TF[TaskFailover.failoverTask]
    TF --> Event[发布 TaskFailoverLifecycleEvent]
    Event --> Handler[TaskFailoverLifecycleEventHandler.handle]
    Handler --> State[TaskRunningStateAction.failoverEventAction]
    State --> Failover[TaskExecutionRunnable.failover]
    
    Failover --> TakeOver{尝试接管任务<br/>reassignMasterHost}
    TakeOver --> RPC[RPC 调用 Worker]
    
    RPC --> RPCResult{RPC 调用结果}
    RPCResult -->|成功 true| Success([接管成功<br/>无需重建任务实例])
    RPCResult -->|失败 false| Create[创建新的故障转移任务实例]
    
    Create --> Clone[克隆原任务实例]
    Clone --> NewTask[插入新任务实例<br/>state=SUBMITTED_SUCCESS<br/>host=null]
    Clone --> OldTask[更新原任务实例<br/>state=NEED_FAULT_TOLERANCE<br/>flag=NO]
    NewTask --> Release[释放任务组资源]
    OldTask --> Release
    Release --> Init[重新初始化 TaskExecutionContext]
    Init --> Start[发布 TaskStartLifecycleEvent]
    Start --> Redispatch[重新分发任务到新 Worker]
    Redispatch --> End([任务继续执行])
    
    Loop -->|还有任务| Loop
    Loop -->|所有任务处理完成| Persist[持久化故障转移状态]
    Persist --> Complete([故障转移完成])
    
    style Start fill:#ffcccc
    style End fill:#ccffcc
    style Cancel fill:#ffffcc
    style Skip fill:#ffffcc
    style Success fill:#ccffcc
    style Complete fill:#ccffcc
```

## 5. 组件交互图

```mermaid
graph TB
    subgraph "注册中心层"
        ZK[ZooKeeper<br/>注册中心]
        Registry[RegistryClient<br/>注册客户端]
    end
    
    subgraph "监控层"
        WCM[WorkerClusters<br/>Worker集群管理器]
        CSM[ClusterStateMonitors<br/>集群状态监控器]
    end
    
    subgraph "事件层"
        SEB[SystemEventBus<br/>系统事件总线]
        WFEH[WorkerFailoverEventHandler<br/>Worker故障转移事件处理器]
        WER[WorkflowEventBus<br/>工作流事件总线]
        TFLEH[TaskFailoverLifecycleEventHandler<br/>任务故障转移生命周期事件处理器]
    end
    
    subgraph "协调层"
        FC[FailoverCoordinator<br/>故障转移协调器]
        TF[TaskFailover<br/>任务故障转移]
    end
    
    subgraph "状态机层"
        TRSA[TaskRunningStateAction<br/>任务运行状态动作]
        ATSA[AbstractTaskStateAction<br/>抽象任务状态动作]
    end
    
    subgraph "执行层"
        TER[TaskExecutionRunnable<br/>任务执行Runnable]
        FTIF[FailoverTaskInstanceFactory<br/>故障转移任务实例工厂]
    end
    
    subgraph "客户端层"
        TEC[TaskExecutorClient<br/>任务执行器客户端]
        PECD[PhysicalTaskExecutorClientDelegator<br/>物理任务执行器客户端委托器]
    end
    
    subgraph "Worker层"
        Worker1[Worker1<br/>故障节点]
        Worker2[Worker2<br/>新节点]
    end
    
    subgraph "持久化层"
        DB[(数据库)]
    end
    
    ZK -->|节点移除事件| Registry
    Registry -->|REMOVE 事件| WCM
    WCM -->|onServerRemove| CSM
    CSM -->|发布延迟事件| SEB
    SEB -->|WorkerFailoverEvent| WFEH
    WFEH -->|failoverWorker| FC
    FC -->|failoverTask| TF
    TF -->|TaskFailoverLifecycleEvent| WER
    WER -->|handle| TFLEH
    TFLEH -->|failoverEventAction| TRSA
    TRSA -->|failoverTask| ATSA
    ATSA -->|failover| TER
    TER -->|reassignWorkflowInstanceHost| TEC
    TEC -->|reassignMasterHost| PECD
    PECD <-->|RPC 调用| Worker1
    TER -->|createTaskInstance| FTIF
    FTIF <-->|读写| DB
    TER -->|TaskStartLifecycleEvent| WER
    PECD -->|dispatchTask| Worker2
    
    style CSM fill:#e1f5ff
    style FC fill:#fff4e1
    style TER fill:#e1ffe1
    style Worker1 fill:#ffcccc
    style Worker2 fill:#ccffcc
    style DB fill:#f0f0f0
```

## 6. 类图

```mermaid
classDiagram
    class ClusterStateMonitors {
        -ClusterManager clusterManager
        -SystemEventBus systemEventBus
        +start()
        +workerRemoved(WorkerServerMetadata)
    }
    
    class WorkerFailoverEvent {
        -WorkerServerMetadata workerServerMetadata
        -Date eventTime
        -long delayTime
        +of(WorkerServerMetadata, Date, long)
    }
    
    class WorkerFailoverEventHandler {
        -FailoverCoordinator failoverCoordinator
        +handle(WorkerFailoverEvent)
        +matchState() SystemEventType
    }
    
    class FailoverCoordinator {
        -WorkflowInstanceDao workflowInstanceDao
        -ClusterManager clusterManager
        -TaskFailover taskFailover
        -RegistryClient registryClient
        +failoverWorker(WorkerFailoverEvent)
        -doWorkerFailover(String, long, String)
        -getFailoverTaskForWorker(String, Date) List~ITaskExecutionRunnable~
    }
    
    class TaskFailover {
        +failoverTask(ITaskExecutionRunnable)
    }
    
    class TaskFailoverLifecycleEvent {
        -ITaskExecutionRunnable taskExecutionRunnable
        +of(ITaskExecutionRunnable)
    }
    
    class TaskFailoverLifecycleEventHandler {
        +handle(ITaskStateAction, IWorkflowExecutionRunnable, ITaskExecutionRunnable, TaskFailoverLifecycleEvent)
        +matchEventType() ILifecycleEventType
    }
    
    class TaskRunningStateAction {
        +failoverEventAction(IWorkflowExecutionRunnable, ITaskExecutionRunnable, TaskFailoverLifecycleEvent)
        +matchState() TaskExecutionStatus
    }
    
    class AbstractTaskStateAction {
        #failoverTask(ITaskExecutionRunnable)
    }
    
    class TaskExecutionRunnable {
        -TaskInstance taskInstance
        -TaskExecutionContext taskExecutionContext
        +failover()
        -takeOverTaskFromExecutor() boolean
        -initializeTaskExecutionContext()
    }
    
    class TaskExecutorClient {
        -LogicTaskExecutorClientDelegator logicTaskExecutorClientDelegator
        -PhysicalTaskExecutorClientDelegator physicalTaskExecutorClientDelegator
        +reassignWorkflowInstanceHost(ITaskExecutionRunnable) boolean
    }
    
    class PhysicalTaskExecutorClientDelegator {
        -MasterConfig masterConfig
        -IWorkerLoadBalancer workerLoadBalancer
        +reassignMasterHost(ITaskExecutionRunnable) boolean
        +dispatch(ITaskExecutionRunnable)
    }
    
    class FailoverTaskInstanceFactory {
        -TaskInstanceDao taskInstanceDao
        -ITaskGroupCoordinator taskGroupCoordinator
        +createTaskInstance(FailoverTaskInstanceBuilder) TaskInstance
    }
    
    ClusterStateMonitors --> WorkerFailoverEvent : 发布
    WorkerFailoverEvent --> WorkerFailoverEventHandler : 被处理
    WorkerFailoverEventHandler --> FailoverCoordinator : 调用
    FailoverCoordinator --> TaskFailover : 调用
    TaskFailover --> TaskFailoverLifecycleEvent : 发布
    TaskFailoverLifecycleEvent --> TaskFailoverLifecycleEventHandler : 被处理
    TaskFailoverLifecycleEventHandler --> TaskRunningStateAction : 调用
    TaskRunningStateAction --> AbstractTaskStateAction : 继承
    AbstractTaskStateAction --> TaskExecutionRunnable : 调用
    TaskExecutionRunnable --> TaskExecutorClient : 调用
    TaskExecutorClient --> PhysicalTaskExecutorClientDelegator : 委托
    TaskExecutionRunnable --> FailoverTaskInstanceFactory : 创建新实例
```

## 7. 架构图

```mermaid
graph TB
    subgraph "故障检测模块"
        A1[ZooKeeper 注册中心]
        A2[RegistryClient]
        A3[WorkerClusters]
        A4[ClusterStateMonitors]
    end
    
    subgraph "事件总线模块"
        B1[SystemEventBus]
        B2[WorkflowEventBus]
    end
    
    subgraph "故障转移模块"
        C1[WorkerFailoverEventHandler]
        C2[FailoverCoordinator]
        C3[TaskFailover]
    end
    
    subgraph "任务处理模块"
        D1[TaskFailoverLifecycleEventHandler]
        D2[TaskRunningStateAction]
        D3[TaskExecutionRunnable]
    end
    
    subgraph "客户端模块"
        E1[TaskExecutorClient]
        E2[PhysicalTaskExecutorClientDelegator]
    end
    
    subgraph "工厂模块"
        F1[FailoverTaskInstanceFactory]
    end
    
    subgraph "数据持久化模块"
        G1[(数据库)]
    end
    
    subgraph "Worker节点"
        H1[Worker1 故障节点]
        H2[Worker2 新节点]
    end
    
    A1 -->|节点移除| A2
    A2 -->|订阅事件| A3
    A3 -->|监听回调| A4
    A4 -->|发布延迟事件| B1
    B1 -->|WorkerFailoverEvent| C1
    C1 -->|failoverWorker| C2
    C2 -->|failoverTask| C3
    C3 -->|TaskFailoverLifecycleEvent| B2
    B2 -->|事件分发| D1
    D1 -->|failoverEventAction| D2
    D2 -->|failover| D3
    D3 -->|reassignWorkflowInstanceHost| E1
    E1 -->|reassignMasterHost| E2
    E2 <-->|RPC 调用| H1
    D3 -->|createTaskInstance| F1
    F1 <-->|读写| G1
    D3 -->|TaskStartLifecycleEvent| B2
    E2 -->|dispatchTask| H2
    
    style A1 fill:#e1f5ff
    style B1 fill:#fff4e1
    style C2 fill:#ffe1f5
    style D3 fill:#e1ffe1
    style E2 fill:#f5e1ff
    style F1 fill:#ffe1e1
    style H1 fill:#ffcccc
    style H2 fill:#ccffcc
    style G1 fill:#f0f0f0
```

## 8. 状态流转图

```mermaid
stateDiagram-v2
    [*] --> Worker正常: 任务分发
    Worker正常 --> Worker故障检测: Worker节点移除
    
    Worker故障检测 --> 延迟确认: 30秒延迟
    延迟确认 --> Worker恢复: Worker重新连接<br/>（取消故障转移）
    延迟确认 --> 故障转移处理: Worker未恢复
    
    Worker恢复 --> [*]
    
    故障转移处理 --> 查询故障任务: 筛选条件匹配
    查询故障任务 --> 尝试接管任务: 遍历每个任务
    
    尝试接管任务 --> 接管成功: RPC调用成功
    尝试接管任务 --> 接管失败: RPC调用失败
    
    接管成功 --> 任务继续执行: 无需重建任务实例
    任务继续执行 --> [*]
    
    接管失败 --> 创建故障转移实例: FailoverTaskInstanceFactory
    创建故障转移实例 --> 原任务标记: NEED_FAULT_TOLERANCE
    创建故障转移实例 --> 新任务创建: SUBMITTED_SUCCESS
    
    新任务创建 --> 重新初始化上下文: TaskExecutionContext
    重新初始化上下文 --> 重新分发任务: TaskStartLifecycleEvent
    重新分发任务 --> 任务继续执行: 新Worker执行
    
    原任务标记 --> [*]
    
    note right of 延迟确认
        30秒延迟机制避免误触发
        网络抖动可能导致临时断开
    end note
    
    note right of 尝试接管任务
        试探性操作确认Worker是否真的故障
        如果Worker可达且仍在运行任务，
        只需更新workflowHost指向新Master
    end note
    
    note right of 创建故障转移实例
        原任务实例：state=NEED_FAULT_TOLERANCE
        新任务实例：state=SUBMITTED_SUCCESS, host=null
    end note
```

## 9. 标记为需要故障转移的条件和流程

### 9.1 需要故障转移的任务状态

根据 `FailoverCoordinator.getFailoverTaskForWorker()` 方法的过滤条件，以下状态的 Task 需要故障转移：

| 状态码 | 状态名称 | 说明 |
|--------|---------|------|
| 17 | DISPATCH | 任务已分派到 Worker，但尚未开始执行 |
| 1 | RUNNING_EXECUTION | 任务正在 Worker 上执行中 |

**判断逻辑**：
- 这些状态都是**非终态**（未完成）状态
- 如果 Worker 故障，这些任务无法继续执行，需要被故障转移
- **已完成或失败的任务**（如 `SUCCESS`、`FAILURE`、`KILLED`、`PAUSED`）不需要故障转移

**代码位置**：
- 状态过滤：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L443-L446)

### 9.2 任务故障转移判断流程

```mermaid
sequenceDiagram
    participant FC as FailoverCoordinator
    participant WR as WorkflowRepository
    participant WEG as WorkflowExecutionGraph
    participant TER as ITaskExecutionRunnable
    participant TI as TaskInstance
    participant TF as TaskFailover
    participant WER as WorkflowEventBus
    
    Note over FC,WER: 步骤1: 从内存中获取所有工作流
    FC->>WR: getAll()
    WR-->>FC: List<IWorkflowExecutionRunnable>
    
    Note over FC,WER: 步骤2: 对每个工作流获取其执行图
    loop 每个工作流
        FC->>WEG: getWorkflowExecutionGraph()
        WEG-->>FC: WorkflowExecutionGraph
        
        Note over FC,WER: 步骤3: 获取所有活跃的任务执行 Runnable
        FC->>WEG: getActiveTaskExecutionRunnable()
        WEG-->>FC: List<ITaskExecutionRunnable>
        
        loop 每个任务执行 Runnable
            Note over FC,WER: 步骤4: 过滤符合条件的任务
            
            FC->>TER: isTaskInstanceInitialized()
            TER-->>FC: true/false
            
            alt 任务实例未初始化
                FC->>FC: 跳过此任务<br/>说明任务尚未分派
            else 任务实例已初始化
                FC->>TER: getTaskInstance()
                TER-->>FC: TaskInstance
                FC->>TI: getHost()
                TI-->>FC: String host
                
                alt host != workerAddress
                    FC->>FC: 跳过此任务<br/>说明不属于故障 Worker
                else host == workerAddress
                    FC->>TI: getState()
                    TI-->>FC: TaskExecutionStatus state
                    
                    alt state != DISPATCH && state != RUNNING_EXECUTION
                        FC->>FC: 跳过此任务<br/>说明已完成或失败，不需要转移
                    else state == DISPATCH || state == RUNNING_EXECUTION
                        FC->>TI: getSubmitTime()
                        TI-->>FC: Date submitTime
                        
                        alt submitTime == null || submitTime >= deadline
                            FC->>FC: 跳过此任务<br/>说明是在 Worker 故障后提交的
                        else submitTime < deadline
                            FC->>FC: 添加到故障转移列表 ✓
                        end
                    end
                end
            end
        end
    end
    
    Note over FC,WER: 步骤5: 对每个需要故障转移的任务执行故障转移
    loop 每个需要故障转移的任务
        FC->>TF: failoverTask(taskExecutionRunnable)
        TF->>WER: publish(TaskFailoverLifecycleEvent)
        WER->>WER: 事件分发到 WorkflowEventBus
    end
```

### 9.3 关键判断条件说明

**1. 任务实例初始化检查（isTaskInstanceInitialized）**
```java
// FailoverCoordinator.getFailoverTaskForWorker() 第437行
.filter(ITaskExecutionRunnable::isTaskInstanceInitialized)
```
- **目的**：确保任务已经初始化，存在 `TaskInstance`
- **原因**：只有已初始化的任务才会被分派到 Worker
- **过滤条件**：如果 `taskInstance == null` 或未初始化，跳过该任务

**2. 主机地址匹配（host == workerAddress）**
```java
// FailoverCoordinator.getFailoverTaskForWorker() 第439-440行
.filter(taskExecutionRunnable -> workerAddress
        .equals(taskExecutionRunnable.getTaskInstance().getHost()))
```
- **目的**：只故障转移分派到故障 Worker 的任务
- **原因**：其他 Worker 上的任务不受影响，不需要转移
- **过滤条件**：`taskInstance.getHost()` 必须等于故障 Worker 地址

**3. 任务状态过滤（DISPATCH 或 RUNNING_EXECUTION）**
```java
// FailoverCoordinator.getFailoverTaskForWorker() 第443-446行
.filter(taskExecutionRunnable -> {
    final TaskExecutionStatus state = taskExecutionRunnable.getTaskInstance().getState();
    return state == TaskExecutionStatus.DISPATCH || state == TaskExecutionStatus.RUNNING_EXECUTION;
})
```
- **目的**：只故障转移正在执行或已分派但未完成的任务
- **原因**：
  - `DISPATCH`：任务已分派但尚未开始执行
  - `RUNNING_EXECUTION`：任务正在执行中
  - `SUCCESS`、`FAILURE`、`KILLED`、`PAUSED`：已完成或失败，不需要转移
- **过滤条件**：状态必须是 `DISPATCH` 或 `RUNNING_EXECUTION`

**4. 提交时间判断（submitTime < deadline）**
```java
// FailoverCoordinator.getFailoverTaskForWorker() 第449-452行
.filter(taskExecutionRunnable -> {
    final Date submitTime = taskExecutionRunnable.getTaskInstance().getSubmitTime();
    return submitTime != null && submitTime.before(taskFailoverDeadline);
})
```
- **目的**：只故障转移在 Worker 故障时间点**之前**提交的任务
- **原因**：在 Worker 故障后提交的任务可能状态异常，不应该被故障转移
- **过滤条件**：`submitTime != null` 且 `submitTime < taskFailoverDeadline`
- **deadline 计算**：
  - 如果 Worker 已重连：使用 Worker 的启动时间
  - 如果 Worker 未重连：使用故障事件的时间（30秒延迟后的时间）

**5. 内存隔离检查（workflowRepository.getAll()）**
```java
// FailoverCoordinator.getFailoverTaskForWorker() 第430行
return workflowRepository.getAll()
```
- **目的**：每个 Master 只处理自己内存中的任务
- **原因**：
  - 每个 Master 只管理自己负责的工作流实例
  - 通过内存隔离实现并发控制，而不是分布式锁
- **过滤条件**：只查询当前 Master 内存中的工作流

**代码位置**：
- 完整过滤逻辑：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L420-L454)

### 9.4 任务故障转移的完整生命周期

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED_SUCCESS: 任务提交
    SUBMITTED_SUCCESS --> DISPATCH: Master分派到Worker
    
    DISPATCH --> RUNNING_EXECUTION: Worker开始执行
    RUNNING_EXECUTION --> Worker故障检测: Worker节点移除
    
    Worker故障检测 --> 延迟确认: 30秒延迟
    延迟确认 --> Worker恢复: Worker重新连接<br/>（跳过故障转移）
    延迟确认 --> 任务故障转移: Worker未恢复
    
    任务故障转移 --> 尝试接管任务: reassignMasterHost
    尝试接管任务 --> 接管成功: RPC调用成功
    尝试接管任务 --> 接管失败: RPC调用失败
    
    接管成功 --> RUNNING_EXECUTION: 任务继续执行<br/>（无需重建）
    
    接管失败 --> 创建故障转移实例: FailoverTaskInstanceFactory
    创建故障转移实例 --> 原任务标记: NEED_FAULT_TOLERANCE
    创建故障转移实例 --> 新任务创建: SUBMITTED_SUCCESS
    
    新任务创建 --> 重新分发: TaskStartLifecycleEvent
    重新分发 --> DISPATCH: 分派到新Worker
    DISPATCH --> RUNNING_EXECUTION: 新Worker开始执行
    
    RUNNING_EXECUTION --> SUCCESS: 执行成功
    RUNNING_EXECUTION --> FAILURE: 执行失败
    RUNNING_EXECUTION --> KILLED: 被终止
    
    SUCCESS --> [*]
    FAILURE --> [*]
    KILLED --> [*]
    原任务标记 --> [*]
    Worker恢复 --> RUNNING_EXECUTION
    
    note right of 任务故障转移
        故障转移状态
        1. 发布TaskFailoverLifecycleEvent
        2. 尝试接管任务
        3. 如果接管失败，创建新实例
    end note
    
    note right of 创建故障转移实例
        原任务实例：state=NEED_FAULT_TOLERANCE
        新任务实例：state=SUBMITTED_SUCCESS, host=null
    end note
```

### 9.5 故障转移触发场景总结

| 场景 | 触发方式 | 判断条件 | 处理方式 |
|------|---------|---------|---------|
| **Worker故障** | WorkerFailoverEvent | 1. 任务实例已初始化<br/>2. host = 故障Worker<br/>3. state == DISPATCH \|\| RUNNING_EXECUTION<br/>4. submitTime < deadline | 发布TaskFailoverLifecycleEvent，尝试接管或重建任务实例 |
| **Worker重连** | WorkerFailoverEvent延迟检查 | Worker存活且启动时间相同 | 跳过故障转移 |
| **任务已完成** | 状态过滤 | state != DISPATCH && state != RUNNING_EXECUTION | 跳过故障转移 |
| **任务未初始化** | 初始化检查 | !isTaskInstanceInitialized() | 跳过故障转移 |
| **任务不属于故障Worker** | 主机地址过滤 | host != workerAddress | 跳过故障转移 |
| **任务在故障后提交** | 时间过滤 | submitTime >= deadline | 跳过故障转移 |

### 9.6 Worker 故障转移处理对象与 Master 的区别

#### 9.6.1 处理对象对比

| 故障类型 | 处理对象 | 数据来源 | 处理方式 | 说明 |
|---------|---------|---------|---------|------|
| **Master 故障转移** | **工作流实例（WorkflowInstance）** | 数据库查询 | 标记为 FAILOVER，插入恢复 Command | Master 负责工作流的调度和管理 |
| **Worker 故障转移** | **任务实例（TaskInstance）** | 内存中运行的任务 | 发布 TaskFailoverLifecycleEvent | Worker 只负责执行任务 |

#### 9.6.2 Master 故障转移处理工作流的原因

**Master 的职责**：
- Master 负责工作流的**调度、管理和监控**
- 工作流实例存储在数据库中，由 Master 负责维护其生命周期
- 当 Master 故障时，其负责的所有工作流都需要被其他 Master 接管

**处理流程**：
1. **查询工作流**：从数据库查询该 Master 负责的所有未完成的工作流
   ```java
   // FailoverCoordinator.getFailoverWorkflowsForMaster()
   workflowInstanceDao.queryNeedFailoverWorkflowInstances(masterAddress)
   ```

2. **标记工作流**：将工作流状态更新为 `FAILOVER`
   ```java
   // WorkflowFailover.failoverWorkflow()
   workflowInstanceDao.updateWorkflowInstanceState(id, originalState, FAILOVER)
   ```

3. **插入恢复命令**：插入 `RECOVER_TOLERANCE_FAULT_PROCESS` 类型的 Command
   ```java
   // WorkflowFailover.failoverWorkflow()
   commandDao.insert(Command.builder()
       .commandType(RECOVER_TOLERANCE_FAULT_PROCESS)
       .workflowInstanceId(workflowInstance.getId())
       .build())
   ```

4. **后续处理**：CommandEngine 会处理恢复命令，重新调度工作流执行
   - 工作流恢复后，其中的任务会重新被调度到可用的 Worker 执行
   - **任务实例的故障转移在工作流恢复时自动处理**

#### 9.6.3 Worker 故障转移只处理任务的原因

**Worker 的职责**：
- Worker 只负责**执行任务**，不管理工作流
- 任务实例在内存中运行，由 Master 监控和管理
- 当 Worker 故障时，只需要将该 Worker 正在执行的任务转移到其他 Worker

**处理流程**：
1. **查询任务**：从内存中查询该 Worker 正在执行的所有任务
   ```java
   // FailoverCoordinator.getFailoverTaskForWorker()
   workflowRepository.getAll()
       .stream()
       .flatMap(graph -> graph.getActiveTaskExecutionRunnable().stream())
       .filter(task -> workerAddress.equals(task.getTaskInstance().getHost()))
       .filter(task -> task.getState() == DISPATCH || task.getState() == RUNNING_EXECUTION)
   ```

2. **发布任务故障转移事件**：为每个任务发布 `TaskFailoverLifecycleEvent`
   ```java
   // TaskFailover.failoverTask()
   taskExecutionRunnable.getWorkflowEventBus().publish(TaskFailoverLifecycleEvent.of(taskExecutionRunnable))
   ```

3. **任务重新调度**：任务状态机处理故障转移事件，将任务重新调度到其他 Worker
   - 任务会重新进入调度队列，等待分配到可用的 Worker
   - **工作流不受影响，继续运行**

#### 9.6.4 为什么 Master 故障转移不直接处理任务？

**原因分析**：

1. **工作流是管理单元**：
   - 工作流是任务的组织单元，Master 管理的是工作流级别
   - 工作流故障转移后，其中的任务会在工作流恢复时自动重新调度

2. **数据一致性**：
   - 工作流状态存储在数据库中，需要统一管理
   - 如果直接处理任务，可能导致工作流状态不一致

3. **恢复粒度**：
   - 工作流级别的恢复可以保证整个工作流的完整性
   - 任务级别的恢复可能无法保证工作流的状态一致性

4. **简化设计**：
   - 工作流恢复时，CommandEngine 会重新解析 DAG 并调度任务
   - 这样可以确保任务调度的正确性和一致性

#### 9.6.5 总结

```mermaid
graph TB
    subgraph "Master故障转移"
        A[Master故障] --> B[查询工作流实例]
        B --> C[标记工作流为FAILOVER]
        C --> D[插入恢复Command]
        D --> E[CommandEngine处理]
        E --> F[重新调度工作流]
        F --> G[工作流中的任务自动重新调度]
    end
    
    subgraph "Worker故障转移"
        H[Worker故障] --> I[查询任务实例]
        I --> J[发布TaskFailoverLifecycleEvent]
        J --> K[任务状态机处理]
        K --> L[任务重新调度到其他Worker]
        L --> M[工作流继续运行]
    end
    
    style A fill:#ffcccc
    style H fill:#ccffcc
    style C fill:#ffffcc
    style J fill:#ffffcc
```

**关键区别**：
- **Master 故障转移**：工作流级别 → 数据库持久化 → 通过 Command 恢复 → 任务自动重新调度
- **Worker 故障转移**：任务级别 → 内存中处理 → 直接重新调度 → 工作流不受影响

## 10. 场景分析与处理方案

### 10.1 场景1：网络抖动导致临时断开

**场景描述**：
- Worker 因网络抖动临时断开与 ZooKeeper 的连接
- 30 秒内 Worker 重新连接
- 任务实际仍在 Worker 上正常执行

**处理方案**：
1. **延迟确认机制**：`ClusterStateMonitors.workerRemoved()` 发布延迟 30 秒的 `WorkerFailoverEvent`
2. **事件取消**：如果 Worker 在 30 秒内重新连接，延迟事件可以被取消
3. **启动时间检查**：`FailoverCoordinator.failoverWorker()` 检查 Worker 是否存活且启动时间相同
4. **跳过故障转移**：如果是误报（网络抖动），跳过故障转移

**代码位置**：
- 延迟确认：[`ClusterStateMonitors#workerRemoved()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)
- 启动时间检查：[`FailoverCoordinator#failoverWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L338-L346)

### 10.2 场景2：Worker 进程崩溃

**场景描述**：
- Worker 进程因异常崩溃
- 30 秒内 Worker 未恢复
- 任务状态为 `DISPATCH` 或 `RUNNING_EXECUTION`

**处理方案**：
1. **故障检测**：ZooKeeper 检测到节点移除，触发故障转移流程
2. **任务查询**：从 Master 内存中查询状态为 `DISPATCH` 或 `RUNNING_EXECUTION` 且 host 匹配故障 Worker 的任务
3. **接管尝试**：尝试通过 RPC 调用 Worker，确认 Worker 是否真的故障
4. **创建新实例**：如果接管失败，创建新的故障转移任务实例
5. **重新分发**：通过负载均衡器选择新的 Worker，重新分发任务

**代码位置**：
- 任务查询：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L420-L457)
- 创建新实例：[`FailoverTaskInstanceFactory#createTaskInstance()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L47-L69)

### 10.3 场景3：Worker 正常但任务已失败

**场景描述**：
- Worker 正常运行
- 任务执行失败，状态为 `FAILURE`
- 不应触发故障转移

**处理方案**：
1. **状态过滤**：`getFailoverTaskForWorker()` 只筛选状态为 `DISPATCH` 或 `RUNNING_EXECUTION` 的任务
2. **已完成任务排除**：状态为 `SUCCESS`、`FAILURE`、`KILLED`、`PAUSED` 的任务不会被故障转移
3. **原任务标记**：如果任务已在失败状态，不需要故障转移

**代码位置**：
- 状态过滤：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L426-L429)

### 10.4 场景4：任务已在 Worker 上执行，Worker 故障但任务快完成

**场景描述**：
- 任务已在 Worker 上执行，接近完成
- Worker 突然故障
- 任务进度丢失

**处理方案**：
1. **接管尝试**：尝试通过 RPC 调用 Worker，确认 Worker 是否真的故障
2. **如果 Worker 可达**：说明 Worker 恢复正常，只需更新 workflowHost
3. **如果 Worker 不可达**：创建新的故障转移任务实例，重新执行
4. **任务幂等性**：如果任务支持幂等性，重新执行不会产生副作用；否则可能需要从检查点恢复

**代码位置**：
- 接管尝试：[`TaskExecutionRunnable#takeOverTaskFromExecutor()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L410-L422)
- RPC 调用：[`PhysicalTaskExecutorClientDelegator#reassignMasterHost()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L107-L159)

### 10.5 场景5：多个 Master 同时检测到 Worker 故障

**场景描述**：
- 多个 Master 节点同时运行
- 所有 Master 都检测到同一个 Worker 故障
- 可能导致重复处理

**处理方案**：
1. **内存隔离**：每个 Master 只处理自己内存中的任务（`workflowRepository.getAll()`）
2. **工作流唯一性**：每个工作流实例只由一个 Master 管理（通过 Command 的唯一性保证）
3. **任务唯一性**：任务属于工作流实例，因此任务也只由一个 Master 处理
4. **无分布式锁**：通过内存隔离实现并发控制，而不是分布式锁
5. **原任务标记**：原任务实例被标记为 `NEED_FAULT_TOLERANCE`，不会被重复处理

**代码位置**：
- 内存查询：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L430-L431)
- 原任务标记：[`FailoverTaskInstanceFactory#createTaskInstance()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L65-L67)

### 10.6 场景6：任务组资源占用

**场景描述**：
- 任务使用了任务组资源
- Worker 故障需要故障转移
- 需要释放任务组资源

**处理方案**：
1. **资源释放检查**：`FailoverTaskInstanceFactory.createTaskInstance()` 检查是否需要释放任务组资源
2. **资源释放**：如果任务使用了任务组，调用 `taskGroupCoordinator.releaseTaskGroupSlot()` 释放资源
3. **新任务重新申请**：新任务实例可以重新申请任务组资源

**代码位置**：
- 资源释放：[`FailoverTaskInstanceFactory#createTaskInstance()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L61-L63)

## 11. Worker 故障转移事件驱动架构

### 11.1 发布-订阅模式 - 事件总线架构

Worker 故障转移采用**发布-订阅（Pub-Sub）模式**的事件驱动架构，实现了监控层与处理层的解耦。

```mermaid
sequenceDiagram
    participant Publisher as 发布者<br/>ClusterStateMonitors
    participant EventBus as SystemEventBus<br/>事件总线(延迟队列)
    participant Subscriber as SystemEventBusFireWorker<br/>订阅者(消费者线程)
    participant Handler as WorkerFailoverEventHandler
    
    Note over Publisher,Handler: 发布阶段
    Publisher->>EventBus: publish(WorkerFailoverEvent, delay=30s)
    
    Note over EventBus,Handler: 存储阶段(延迟队列)
    EventBus->>EventBus: 事件按延迟时间排序<br/>DelayQueue.offer(event)
    Note over EventBus: 事件在队列中等待30秒到期
    
    Note over Subscriber,Handler: 订阅消费阶段
    loop 持续监听
        Subscriber->>EventBus: take() (阻塞等待)
        EventBus->>EventBus: 检查事件是否到期<br/>getDelay() <= 0?
        alt 事件未到期
            EventBus->>EventBus: Condition.awaitNanos(delay)<br/>阻塞等待剩余时间
        else 事件已到期
            EventBus-->>Subscriber: 返回到期的事件
            Subscriber->>Subscriber: fireSystemEvent(event)
            
            Note over Subscriber,Handler: 匹配Handler策略
            Subscriber->>Handler: matchState() == WORKER_FAILOVER?
            Handler-->>Subscriber: true
            
            Subscriber->>Handler: handle(WorkerFailoverEvent)
            Handler->>Handler: failoverCoordinator<br/>.failoverWorker(event)
        end
    end
```

```mermaid
graph TB
    subgraph "发布者 Publisher"
        CSM[ClusterStateMonitors<br/>集群状态监控器]
    end
    
    subgraph "事件总线 Event Bus"
        SEB[SystemEventBus<br/>DelayQueue~AbstractSystemEvent~]
        direction TB
        SEB -->|延迟队列| Queue[DelayQueue<br/>按过期时间排序]
        Queue -->|存储| WFE[WorkerFailoverEvent<br/>延迟30秒]
    end
    
    subgraph "订阅者 Subscriber"
        SEFW[SystemEventBusFireWorker<br/>守护线程]
    end
    
    subgraph "事件处理器 Handler"
        WFH[WorkerFailoverEventHandler<br/>Worker故障转移事件处理器]
    end
    
    subgraph "故障转移协调器"
        FC[FailoverCoordinator<br/>故障转移协调器]
    end
    
    CSM -->|publish| SEB
    SEB -->|存储到延迟队列| Queue
    Queue -->|30秒后到期| WFE
    SEFW -->|take阻塞等待| Queue
    WFE -->|到期事件| SEFW
    SEFW -->|匹配Handler| WFH
    WFH -->|failoverWorker| FC
    
    style SEB fill:#e1f5ff
    style Queue fill:#fff4e1
    style SEFW fill:#e8f5e9
    style WFH fill:#f3e5f5
    style FC fill:#fff9c4
```

### 11.2 策略模式 - Event 和 Handler 匹配策略

系统事件处理采用**策略模式**，通过事件类型动态匹配对应的处理器。

```mermaid
classDiagram
    class SystemEventBusFireWorker {
        -SystemEventBus systemEventBus
        -List~ISystemEventHandler~ systemEventHandlers
        +run() void
        +fireSystemEvent(AbstractSystemEvent) void
    }
    
    class ISystemEventHandler~T~ {
        <<interface>>
        +handle(T event) void
        +matchState() SystemEventType
    }
    
    class WorkerFailoverEventHandler {
        -FailoverCoordinator failoverCoordinator
        +matchState() SystemEventType
        +handle(WorkerFailoverEvent) void
    }
    
    class GlobalMasterFailoverEventHandler {
        +matchState() SystemEventType
        +handle(GlobalMasterFailoverEvent) void
    }
    
    class MasterFailoverEventHandler {
        +matchState() SystemEventType
        +handle(MasterFailoverEvent) void
    }
    
    class AbstractSystemEvent {
        <<abstract>>
        +getEventType() SystemEventType
    }
    
    class WorkerFailoverEvent {
        -WorkerServerMetadata workerServerMetadata
        -Date eventTime
        +getEventType() SystemEventType
    }
    
    class SystemEventType {
        <<enumeration>>
        GLOBAL_MASTER_FAILOVER
        MASTER_FAILOVER
        WORKER_FAILOVER
    }
    
    SystemEventBusFireWorker --> ISystemEventHandler : 持有Handler列表
    ISystemEventHandler <|.. WorkerFailoverEventHandler : 策略1
    ISystemEventHandler <|.. GlobalMasterFailoverEventHandler : 策略2
    ISystemEventHandler <|.. MasterFailoverEventHandler : 策略3
    
    AbstractSystemEvent <|-- WorkerFailoverEvent
    AbstractSystemEvent <|-- GlobalMasterFailoverEvent
    AbstractSystemEvent <|-- MasterFailoverEvent
    
    SystemEventBusFireWorker ..> AbstractSystemEvent : 接收事件
    SystemEventBusFireWorker ..> ISystemEventHandler : 根据matchState()匹配Handler
    
    WorkerFailoverEvent --> SystemEventType : WORKER_FAILOVER
    GlobalMasterFailoverEvent --> SystemEventType : GLOBAL_MASTER_FAILOVER
    MasterFailoverEvent --> SystemEventType : MASTER_FAILOVER
    
    WorkerFailoverEventHandler --> SystemEventType : matchState()返回WORKER_FAILOVER
    GlobalMasterFailoverEventHandler --> SystemEventType : matchState()返回GLOBAL_MASTER_FAILOVER
    MasterFailoverEventHandler --> SystemEventType : matchState()返回MASTER_FAILOVER
    
    note for SystemEventBusFireWorker "策略选择逻辑:\n1. 遍历所有Handler\n2. 调用matchState()获取Handler的EventType\n3. 与Event的getEventType()比较\n4. 匹配则调用handle()方法"
    note for ISystemEventHandler "策略接口:\n每个Handler实现matchState()\n返回自己处理的EventType"
```

**策略匹配流程**：

```java
// SystemEventBusFireWorker.fireSystemEvent()
private void fireSystemEvent(final AbstractSystemEvent systemEvent) {
    // 1. 从所有注册的事件处理器中筛选出与当前事件类型匹配的处理器
    final List<ISystemEventHandler> matchedSystemEventHandlers = systemEventHandlers
            .stream()
            .filter(systemEventHandler -> systemEventHandler.matchState() == systemEvent.getEventType())
            .collect(Collectors.toList());
    
    // 2. 遍历所有匹配的处理器，依次执行处理逻辑
    matchedSystemEventHandlers.forEach(systemEventHandler -> systemEventHandler.handle(systemEvent));
}
```

### 11.3 Worker 故障转移事件驱动完整流程

```mermaid
sequenceDiagram
    participant ZK as ZooKeeper<br/>注册中心
    participant TC as TreeCache
    participant Registry as RegistryClient
    participant WCM as WorkerClusters
    participant CSM as ClusterStateMonitors
    participant WFE as WorkerFailoverEvent
    participant SEB as SystemEventBus<br/>延迟事件队列
    participant SEFW as SystemEventBusFireWorker<br/>消费线程
    participant WFH as WorkerFailoverEventHandler
    participant FC as FailoverCoordinator
    participant TF as TaskFailover
    participant WER as WorkflowEventBus
    
    Note over ZK,WER: 阶段1: 事件发布（延迟30秒）
    ZK->>TC: 节点移除事件
    TC->>Registry: childEvent(REMOVE)
    Registry->>WCM: onServerRemove(workerServer)
    WCM->>CSM: workerRemoved(workerServer)
    CSM->>WFE: WorkerFailoverEvent.of(workerServer, new Date(), 30_000)
    CSM->>SEB: publish(WorkerFailoverEvent, delay=30s)
    SEB->>SEB: DelayQueue.add(event)<br/>按过期时间排序
    
    Note over SEB: 阶段2: 事件等待（延迟30秒）
    Note over SEB: 事件在队列中等待30秒到期<br/>DelayQueue自动排序和阻塞
    
    Note over SEFW,WER: 阶段3: 事件消费（30秒后）
    SEFW->>SEB: take() (阻塞等待)
    SEB->>WFE: getDelay(TimeUnit.NANOSECONDS)
    WFE-->>SEB: delay <= 0 (已到期)
    SEB-->>SEFW: WorkerFailoverEvent
    
    Note over SEFW,WER: 阶段4: 事件处理和匹配
    SEFW->>SEFW: fireSystemEvent(WorkerFailoverEvent)
    SEFW->>WFH: matchState() == WORKER_FAILOVER?
    WFH-->>SEFW: true (匹配)
    SEFW->>WFH: handle(WorkerFailoverEvent)
    
    Note over WFH,WER: 阶段5: 故障转移处理
    WFH->>FC: failoverWorker(WorkerFailoverEvent)
    FC->>FC: 检查Worker是否存活
    alt Worker已重连
        FC->>FC: 跳过故障转移
    else Worker确实故障
        FC->>FC: getFailoverTaskForWorker()
        loop 每个需要故障转移的任务
            FC->>TF: failoverTask(taskExecutionRunnable)
            TF->>WER: publish(TaskFailoverLifecycleEvent)
        end
        FC->>ZK: 持久化故障转移状态
    end
```

### 11.4 事件驱动架构的核心组件

#### 11.4.1 WorkerFailoverEvent（事件）

**职责**：封装 Worker 故障转移事件信息

```java
@Getter
public class WorkerFailoverEvent extends AbstractSystemEvent {
    private final WorkerServerMetadata workerServerMetadata;  // 故障的Worker元数据
    private final Date eventTime;                              // 事件时间
    private final long delayTime;                              // 延迟时间（30秒）
    
    public static WorkerFailoverEvent of(final WorkerServerMetadata workerServerMetadata,
                                        final Date eventTime,
                                        final long delayTime) {
        return new WorkerFailoverEvent(workerServerMetadata, eventTime, delayTime);
    }
    
    @Override
    public SystemEventType getEventType() {
        return SystemEventType.WORKER_FAILOVER;
    }
}
```

**关键特性**：
- 继承 `AbstractSystemEvent`，实现 `Delayed` 接口
- 延迟时间默认为 30 秒（避免误触发）
- 包含故障 Worker 的元数据信息

**代码位置**：
- 事件定义：[`WorkerFailoverEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEvent.java)
- 事件发布：[`ClusterStateMonitors#workerRemoved()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)

#### 11.4.2 SystemEventBus（事件总线）

**职责**：存储和管理系统事件，支持延迟事件

```java
@Component
public class SystemEventBus extends AbstractDelayEventBus<AbstractSystemEvent> {
    private final DelayQueue<AbstractSystemEvent> delayEventQueue;
    
    public void publish(final AbstractSystemEvent event) {
        delayEventQueue.add(event);  // 添加到延迟队列
    }
    
    public AbstractSystemEvent take() throws InterruptedException {
        return delayEventQueue.take();  // 阻塞等待直到事件到期
    }
}
```

**关键特性**：
- 使用 `DelayQueue` 实现延迟事件队列
- 自动按过期时间排序（最早到期的事件优先）
- `take()` 方法阻塞等待，直到有事件到期

**代码位置**：
- 事件总线：[`SystemEventBus.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/SystemEventBus.java)

#### 11.4.3 SystemEventBusFireWorker（事件消费线程）

**职责**：从事件总线中取出到期的事件并触发相应的处理器

```java
@Component
public class SystemEventBusFireWorker extends BaseDaemonThread {
    @Autowired
    private SystemEventBus systemEventBus;
    
    @Autowired
    private List<ISystemEventHandler> systemEventHandlers;
    
    @Override
    public void run() {
        while (flag) {
            // 1. 阻塞等待，直到有事件到期
            final AbstractSystemEvent systemEvent = systemEventBus.take();
            
            // 2. 触发事件处理
            fireSystemEvent(systemEvent);
        }
    }
    
    private void fireSystemEvent(final AbstractSystemEvent systemEvent) {
        // 1. 匹配Handler
        final List<ISystemEventHandler> matchedHandlers = systemEventHandlers
                .stream()
                .filter(handler -> handler.matchState() == systemEvent.getEventType())
                .collect(Collectors.toList());
        
        // 2. 执行处理
        matchedHandlers.forEach(handler -> handler.handle(systemEvent));
    }
}
```

**关键特性**：
- 守护线程，持续监听事件总线
- 使用策略模式匹配对应的 Handler
- 异常处理：如果处理失败，将事件重新放回队列重试

**代码位置**：
- 消费线程：[`SystemEventBusFireWorker.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/SystemEventBusFireWorker.java)

#### 11.4.4 WorkerFailoverEventHandler（事件处理器）

**职责**：处理 Worker 故障转移事件

```java
@Component
public class WorkerFailoverEventHandler implements ISystemEventHandler<WorkerFailoverEvent> {
    @Autowired
    private FailoverCoordinator failoverCoordinator;
    
    @Override
    public void handle(final WorkerFailoverEvent workerFailoverEvent) {
        failoverCoordinator.failoverWorker(workerFailoverEvent);
    }
    
    @Override
    public SystemEventType matchState() {
        return SystemEventType.WORKER_FAILOVER;  // 匹配WORKER_FAILOVER类型的事件
    }
}
```

**关键特性**：
- 实现 `ISystemEventHandler<WorkerFailoverEvent>` 接口
- `matchState()` 返回 `WORKER_FAILOVER`，用于事件匹配
- `handle()` 方法委托给 `FailoverCoordinator` 执行实际的故障转移逻辑

**代码位置**：
- 事件处理器：[`WorkerFailoverEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEventHandler.java)

### 11.5 事件驱动故障转移完整流程

Worker 故障转移容错涉及多层事件流转，从系统级事件到任务级生命周期事件，形成了一个完整的事件驱动链路。

#### 11.5.1 事件流转链路概览

```mermaid
graph TB
    subgraph "系统级事件（SystemEventBus）"
        WFE[WorkerFailoverEvent<br/>延迟30秒]
        WFH[WorkerFailoverEventHandler]
    end
    
    subgraph "任务生命周期事件（WorkflowEventBus）"
        TFE[TaskFailoverLifecycleEvent]
        TSE[TaskStartLifecycleEvent]
        TDE[TaskDispatchLifecycleEvent]
        TDSE[TaskDispatchedLifecycleEvent]
        TRE[TaskRunningLifecycleEvent]
        TSUCE[TaskSuccessLifecycleEvent<br/>或TaskFailedLifecycleEvent]
    end
    
    subgraph "Handler层"
        TFH[TaskFailoverLifecycleEventHandler]
        TSH[TaskStartLifecycleEventHandler]
        TDH[TaskDispatchLifecycleEventHandler]
        TDSH[TaskDispatchedLifecycleEventHandler]
        TRH[TaskRunningLifecycleEventHandler]
        TSUCH[TaskSuccessLifecycleEventHandler]
    end
    
    subgraph "StateAction层"
        TRSA[TaskRunningStateAction<br/>failoverEventAction]
        TSUSA[TaskSubmittedStateAction<br/>startEventAction<br/>dispatchEventAction]
        TDSA[TaskDispatchStateAction<br/>dispatchedEventAction]
    end
    
    subgraph "故障转移处理"
        FC[FailoverCoordinator]
        TER[TaskExecutionRunnable<br/>failover]
        FTIF[FailoverTaskInstanceFactory]
    end
    
    WFE -->|30秒后| WFH
    WFH -->|failoverWorker| FC
    FC -->|failoverTask| TFE
    TFE -->|匹配Handler| TFH
    TFH -->|failoverEventAction| TRSA
    TRSA -->|failover| TER
    TER -->|takeOver失败| FTIF
    TER -->|创建新实例| TSE
    TSE -->|匹配Handler| TSH
    TSH -->|startEventAction| TSUSA
    TSUSA -->|tryToDispatchTask| TDE
    TDE -->|匹配Handler| TDH
    TDH -->|dispatchEventAction| TSUSA
    TSUSA -->|dispatchTask| TDSE
    TDSE -->|Worker报告| TDSH
    TDSH -->|dispatchedEventAction| TDSA
    TDSE -->|Worker执行| TRE
    TRE -->|Worker报告| TRH
    TRE -->|任务完成| TSUCE
    TSUCE -->|Worker报告| TSUCH
```

#### 11.5.2 完整事件流转时序图

```mermaid
sequenceDiagram
    participant ZK as ZooKeeper
    participant CSM as ClusterStateMonitors
    participant WFE as WorkerFailoverEvent
    participant SEB as SystemEventBus
    participant SEFW as SystemEventBusFireWorker
    participant WFH as WorkerFailoverEventHandler
    participant FC as FailoverCoordinator
    participant TF as TaskFailover
    participant WER as WorkflowEventBus
    participant TFE as TaskFailoverLifecycleEvent
    participant TFH as TaskFailoverLifecycleEventHandler
    participant TRSA as TaskRunningStateAction
    participant TER as TaskExecutionRunnable
    participant FTIF as FailoverTaskInstanceFactory
    participant TSE as TaskStartLifecycleEvent
    participant TSH as TaskStartLifecycleEventHandler
    participant TSUSA as TaskSubmittedStateAction
    participant TDE as TaskDispatchLifecycleEvent
    participant TDH as TaskDispatchLifecycleEventHandler
    participant GTDQ as GlobalTaskDispatchWaitingQueue
    participant TEC as TaskExecutorClient
    participant Worker2 as Worker2（新）
    participant TDSE as TaskDispatchedLifecycleEvent
    participant TDSH as TaskDispatchedLifecycleEventHandler
    participant TDSA as TaskDispatchStateAction
    participant TRE as TaskRunningLifecycleEvent
    participant TRH as TaskRunningLifecycleEventHandler
    participant TSUCE as TaskSuccessLifecycleEvent
    participant TSUCH as TaskSuccessLifecycleEventHandler
    
    Note over ZK,SEFW: 阶段1: 系统级事件（延迟30秒）
    ZK->>CSM: Worker节点移除
    CSM->>WFE: WorkerFailoverEvent.of(workerServer, new Date(), 30_000)
    CSM->>SEB: publish(WorkerFailoverEvent, delay=30s)
    SEB->>SEB: DelayQueue.add(event)<br/>等待30秒到期
    
    Note over SEFW: 30秒后
    SEFW->>SEB: take() (阻塞等待)
    SEB-->>SEFW: WorkerFailoverEvent（已到期）
    SEFW->>WFH: fireSystemEvent(WorkerFailoverEvent)
    WFH->>FC: failoverWorker(WorkerFailoverEvent)
    
    Note over FC,WER: 阶段2: 任务故障转移事件
    FC->>FC: getFailoverTaskForWorker()
    FC->>TF: failoverTask(taskExecutionRunnable)
    TF->>WER: publish(TaskFailoverLifecycleEvent)
    
    Note over WER,TRSA: 阶段3: 任务故障转移处理
    WER->>TFH: handle(TaskFailoverLifecycleEvent)
    TFH->>TRSA: failoverEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskFailoverEvent)
    TRSA->>TRSA: failoverTask(taskExecutionRunnable)
    TRSA->>TER: failover()
    
    Note over TER,FTIF: 阶段4: 任务接管与重建
    TER->>TER: takeOverTaskFromExecutor()<br/>RPC调用Worker1
    alt Worker1可达且接管成功
        TER->>TER: return true<br/>任务继续执行，无需重建
        Note over TER: 只需更新workflowHost
    else Worker1不可达或接管失败
        TER->>FTIF: createTaskInstance()
        FTIF->>FTIF: 创建新任务实例<br/>state=SUBMITTED_SUCCESS
        FTIF->>TER: 返回新taskInstance
        TER->>TER: initializeTaskExecutionContext()
        TER->>WER: publish(TaskStartLifecycleEvent)
    end
    
    Note over WER,TSUSA: 阶段5: 任务重新启动
    WER->>TSH: handle(TaskStartLifecycleEvent)
    TSH->>TSUSA: startEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskStartEvent)
    TSUSA->>TSUSA: tryToDispatchTask(taskExecutionRunnable)
    TSUSA->>WER: publish(TaskDispatchLifecycleEvent)
    
    Note over WER,TDSA: 阶段6: 任务分发
    WER->>TDH: handle(TaskDispatchLifecycleEvent)
    TDH->>TSUSA: dispatchEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskDispatchEvent)
    TSUSA->>GTDQ: dispatchTaskExecuteRunnableWithDelay()
    GTDQ->>TEC: dispatch(taskExecutionRunnable)
    TEC->>Worker2: RPC dispatchTask()
    Worker2->>Worker2: TaskEngine.submitTask()
    Worker2->>Worker2: publish(TaskExecutorDispatchedLifecycleEvent)
    Worker2->>Worker2: 报告给Master
    Worker2->>TEC: TaskDispatchedLifecycleEvent
    TEC->>WER: publish(TaskDispatchedLifecycleEvent)
    
    Note over WER,TDSA: 阶段7: 任务已分派确认
    WER->>TDSH: handle(TaskDispatchedLifecycleEvent)
    TDSH->>TDSA: dispatchedEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskDispatchedEvent)
    TDSH->>TEC: ackTaskExecutorLifecycleEvent()
    TEC->>Worker2: ACK（确认收到）
    
    Note over Worker2,WER: 阶段8: 任务执行中
    Worker2->>Worker2: publish(TaskExecutorStartedLifecycleEvent)
    Worker2->>TEC: TaskRunningLifecycleEvent
    TEC->>WER: publish(TaskRunningLifecycleEvent)
    WER->>TRH: handle(TaskRunningLifecycleEvent)
    TRH->>TRSA: startedEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskRunningEvent)
    TRH->>TEC: ackTaskExecutorLifecycleEvent()
    
    Note over Worker2,WER: 阶段9: 任务完成
    Worker2->>Worker2: publish(TaskExecutorSuccessLifecycleEvent<br/>或TaskExecutorFailedLifecycleEvent)
    Worker2->>TEC: TaskSuccessLifecycleEvent<br/>或TaskFailedLifecycleEvent
    TEC->>WER: publish(TaskSuccessLifecycleEvent)
    WER->>TSUCH: handle(TaskSuccessLifecycleEvent)
    TSUCH->>TRSA: succeedEventAction(workflowExecutionRunnable,<br/>  taskExecutionRunnable,<br/>  taskSuccessEvent)
    TSUCH->>TEC: ackTaskExecutorLifecycleEvent()
```

#### 11.5.3 关键事件详解

##### 11.5.3.1 WorkerFailoverEvent（系统级事件）

**事件类型**：`SystemEventType.WORKER_FAILOVER`  
**发布位置**：[`ClusterStateMonitors#workerRemoved()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)  
**延迟时间**：30秒（避免误触发）  
**Handler**：`WorkerFailoverEventHandler`  
**处理逻辑**：调用 `FailoverCoordinator.failoverWorker()`

**代码位置**：
- 事件定义：[`WorkerFailoverEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEvent.java)
- 事件处理器：[`WorkerFailoverEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEventHandler.java)

##### 11.5.3.2 TaskFailoverLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.FAILOVER`  
**发布位置**：[`TaskFailover#failoverTask()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/TaskFailover.java#L29-L33)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskFailoverLifecycleEventHandler`  
**处理逻辑**：调用 `TaskStateAction.failoverEventAction()`

**代码位置**：
- 事件定义：[`TaskFailoverLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskFailoverLifecycleEvent.java)
- 事件处理器：[`TaskFailoverLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailoverLifecycleEventHandler.java)

##### 11.5.3.3 TaskStartLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.START`  
**发布位置**：[`TaskExecutionRunnable#failover()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L333)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskStartLifecycleEventHandler`  
**处理逻辑**：
- 如果任务实例未初始化，调用 `initializeFirstRunTaskInstance()`
- 调用 `TaskStateAction.startEventAction()`

**代码位置**：
- 事件定义：[`TaskStartLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskStartLifecycleEvent.java)
- 事件处理器：[`TaskStartLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskStartLifecycleEventHandler.java)

##### 11.5.3.4 TaskDispatchLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.DISPATCH`  
**发布位置**：[`AbstractTaskStateAction#tryToDispatchTask()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/AbstractTaskStateAction.java#L235)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskDispatchLifecycleEventHandler`  
**处理逻辑**：调用 `TaskStateAction.dispatchEventAction()`，将任务放入 `GlobalTaskDispatchWaitingQueue`

**代码位置**：
- 事件定义：[`TaskDispatchLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskDispatchLifecycleEvent.java)
- 事件处理器：[`TaskDispatchLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskDispatchLifecycleEventHandler.java)

##### 11.5.3.5 TaskDispatchedLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.DISPATCHED`  
**发布位置**：Worker端通过RPC报告，Master端的 [`TaskDispatchedLifecycleEventHandler`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskDispatchedLifecycleEventHandler.java#L50-L57)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskDispatchedLifecycleEventHandler`  
**处理逻辑**：
- 调用 `TaskStateAction.dispatchedEventAction()`
- 调用 `TaskExecutorClient.ackTaskExecutorLifecycleEvent()` 发送ACK给Worker

**代码位置**：
- 事件定义：[`TaskDispatchedLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskDispatchedLifecycleEvent.java)
- 事件处理器：[`TaskDispatchedLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskDispatchedLifecycleEventHandler.java)

##### 11.5.3.6 TaskRunningLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.RUNNING`  
**发布位置**：Worker端通过RPC报告，Master端的 [`TaskRunningLifecycleEventHandler`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskRunningLifecycleEventHandler.java#L49-L54)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskRunningLifecycleEventHandler`  
**处理逻辑**：
- 调用 `TaskStateAction.startedEventAction()`
- 调用 `TaskExecutorClient.ackTaskExecutorLifecycleEvent()` 发送ACK给Worker

**代码位置**：
- 事件定义：[`TaskRunningLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskRunningLifecycleEvent.java)
- 事件处理器：[`TaskRunningLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskRunningLifecycleEventHandler.java)

##### 11.5.3.7 TaskSuccessLifecycleEvent / TaskFailedLifecycleEvent（任务生命周期事件）

**事件类型**：`TaskLifecycleEventType.SUCCEEDED` / `TaskLifecycleEventType.FAILED`  
**发布位置**：Worker端通过RPC报告，Master端的 [`TaskSuccessLifecycleEventHandler`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskSuccessLifecycleEventHandler.java#L46-L51) / [`TaskFailedLifecycleEventHandler`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailedLifecycleEventHandler.java)  
**延迟时间**：无延迟（立即处理）  
**Handler**：`TaskSuccessLifecycleEventHandler` / `TaskFailedLifecycleEventHandler`  
**处理逻辑**：
- 调用 `TaskStateAction.succeedEventAction()` / `failedEventAction()`
- 调用 `TaskExecutorClient.ackTaskExecutorLifecycleEvent()` 发送ACK给Worker

**代码位置**：
- 事件定义：[`TaskSuccessLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskSuccessLifecycleEvent.java) / [`TaskFailedLifecycleEvent.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/event/TaskFailedLifecycleEvent.java)
- 事件处理器：[`TaskSuccessLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskSuccessLifecycleEventHandler.java) / [`TaskFailedLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailedLifecycleEventHandler.java)

#### 11.5.4 StateAction与任务状态的映射关系

在Worker故障转移过程中，不同的任务状态对应不同的StateAction：

| 任务状态 | StateAction | 关键方法 | 说明 |
|---------|------------|---------|------|
| **RUNNING_EXECUTION** | `TaskRunningStateAction` | `failoverEventAction()` | 处理RUNNING_EXECUTION状态任务的故障转移 |
| **DISPATCH** | `TaskDispatchStateAction` | `failoverEventAction()` | 处理DISPATCH状态任务的故障转移 |
| **SUBMITTED_SUCCESS** | `TaskSubmittedStateAction` | `startEventAction()`<br/>`dispatchEventAction()` | 处理新任务实例的启动和分发 |
| **NEED_FAULT_TOLERANCE** | `TaskFailoverStateAction` | 不处理任何事件 | 原任务实例标记为故障转移状态，不再处理事件 |

**代码位置**：
- `TaskRunningStateAction.failoverEventAction()`：[第133-139行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/TaskRunningStateAction.java#L133-L139)
- `TaskDispatchStateAction.failoverEventAction()`：[第141-146行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/TaskDispatchStateAction.java#L141-L146)
- `AbstractTaskStateAction.failoverTask()`：[第225-227行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/AbstractTaskStateAction.java#L225-L227)

#### 11.5.5 Handler与事件类型的映射关系

所有任务生命周期事件都通过 `WorkflowEventBusFireWorker` 消费，并使用策略模式匹配对应的Handler：

| 事件类型 | Handler | matchEventType()返回值 |
|---------|---------|----------------------|
| **FAILOVER** | `TaskFailoverLifecycleEventHandler` | `TaskLifecycleEventType.FAILOVER` |
| **START** | `TaskStartLifecycleEventHandler` | `TaskLifecycleEventType.START` |
| **DISPATCH** | `TaskDispatchLifecycleEventHandler` | `TaskLifecycleEventType.DISPATCH` |
| **DISPATCHED** | `TaskDispatchedLifecycleEventHandler` | `TaskLifecycleEventType.DISPATCHED` |
| **RUNNING** | `TaskRunningLifecycleEventHandler` | `TaskLifecycleEventType.RUNNING` |
| **SUCCEEDED** | `TaskSuccessLifecycleEventHandler` | `TaskLifecycleEventType.SUCCEEDED` |
| **FAILED** | `TaskFailedLifecycleEventHandler` | `TaskLifecycleEventType.FAILED` |

**代码位置**：
- Handler匹配逻辑：[`WorkflowEventBusFireWorker#fireWorkflowEvent()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/WorkflowEventBusFireWorker.java)

#### 11.5.6 Worker端事件报告机制

Worker端通过 `TaskExecutorEventBus` 发布任务执行器生命周期事件，并通过 `PhysicalTaskExecutorLifecycleEventReporter` 异步报告给Master：

| Worker端事件 | Master端事件 | 报告方式 |
|------------|------------|---------|
| `TaskExecutorDispatchedLifecycleEvent` | `TaskDispatchedLifecycleEvent` | RPC异步报告 |
| `TaskExecutorStartedLifecycleEvent` | `TaskRunningLifecycleEvent` | RPC异步报告 |
| `TaskExecutorRuntimeContextChangedLifecycleEvent` | `TaskRuntimeContextChangedLifecycleEvent` | RPC异步报告 |
| `TaskExecutorSuccessLifecycleEvent` | `TaskSuccessLifecycleEvent` | RPC异步报告 |
| `TaskExecutorFailedLifecycleEvent` | `TaskFailedLifecycleEvent` | RPC异步报告 |

**关键点**：
- Worker端的事件报告是**异步的**，不阻塞任务执行
- Master收到事件后发送ACK确认，Worker收到ACK后才从事件队列中移除
- 如果未收到ACK，Worker会按照重试间隔（默认3分钟）重试发送

**代码位置**：
- Worker端事件报告：[`PhysicalTaskExecutorLifecycleEventReporter.java`](../../../../dolphinscheduler-worker/src/main/java/org/apache/dolphinscheduler/server/worker/executor/PhysicalTaskExecutorLifecycleEventReporter.java)
- Master端事件接收：[`PhysicalTaskExecutorOperatorImpl#receiveTaskExecutorLifecycleEvent()`](../../../../dolphinscheduler-worker/src/main/java/org/apache/dolphinscheduler/server/worker/rpc/PhysicalTaskExecutorOperatorImpl.java)

#### 11.5.7 事件驱动的优势总结

| 优势 | 说明 |
|------|------|
| **解耦设计** | 系统级事件（WorkerFailoverEvent）与任务级事件（TaskFailoverLifecycleEvent）完全解耦 |
| **状态机模式** | 通过StateAction实现状态机模式，不同状态对应不同的处理逻辑 |
| **策略模式** | Handler使用策略模式匹配事件类型，支持扩展新的Handler |
| **异步处理** | 事件处理是异步的，不阻塞主流程 |
| **最终一致性** | Worker端事件报告采用重试机制，保证最终一致性 |
| **可观测性** | 通过事件流转可以清晰地追踪故障转移的完整过程 |

### 11.6 事件驱动架构的优势

| 优势 | 说明 |
|------|------|
| **解耦** | 监控层（ClusterStateMonitors）与处理层（FailoverCoordinator）完全解耦，通过事件总线通信 |
| **延迟执行** | 支持延迟事件（30秒延迟），避免网络抖动导致的误触发 |
| **异步处理** | 事件处理是异步的，不阻塞主线程 |
| **可扩展性** | 通过添加新的 Handler 可以轻松扩展处理逻辑 |
| **策略模式** | 使用策略模式匹配 Handler，支持一个事件类型对应多个处理器 |
| **自动排序** | DelayQueue 自动按过期时间排序，最早到期的事件优先处理 |
| **线程安全** | 所有操作都是线程安全的，支持并发发布和消费 |
| **错误恢复** | 如果处理失败，事件会被重新放回队列，确保事件不丢失 |

### 11.7 与其他系统事件的对比

| 事件类型 | 触发时机 | 延迟时间 | Handler | 处理范围 |
|---------|---------|---------|---------|---------|
| **GlobalMasterFailoverEvent** | MasterServer启动时 | 0毫秒 | GlobalMasterFailoverEventHandler | 扫描所有需要故障转移的Master |
| **MasterFailoverEvent** | 检测到Master移除 | 30秒 | MasterFailoverEventHandler | 特定Master的工作流 |
| **WorkerFailoverEvent** | 检测到Worker移除 | 30秒 | WorkerFailoverEventHandler | 特定Worker的任务 |

**关键区别**：
- `GlobalMasterFailoverEvent`：系统启动时主动扫描，无延迟
- `MasterFailoverEvent` 和 `WorkerFailoverEvent`：被动检测，30秒延迟避免误触发

## 12. 关键设计点

### 12.1 延迟确认机制

**设计原因**：
- 避免网络抖动导致的误触发
- 给 Worker 足够的时间重新连接注册中心
- 减少不必要的故障转移操作

**实现方式**：
- `ClusterStateMonitors.workerRemoved()` 发布延迟 30 秒的 `WorkerFailoverEvent`
- 使用 `SystemEventBus`（`DelayQueue`）实现延迟事件
- 如果 Worker 在延迟期间重新连接，事件可以被取消或忽略

**代码位置**：
- 延迟发布：[`ClusterStateMonitors#workerRemoved()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)

### 12.2 试探性接管机制

**设计原因**：
- 确认 Worker 是否真的故障
- 避免不必要的任务重建
- 如果 Worker 仍然可达，只需更新 workflowHost

**实现方式**：
- `TaskExecutionRunnable.takeOverTaskFromExecutor()` 尝试通过 RPC 调用 Worker
- 调用 `PhysicalTaskExecutorClientDelegator.reassignMasterHost()` 更新 workflowHost
- 如果 RPC 调用成功，说明 Worker 可达，任务仍在运行
- 如果 RPC 调用失败，说明 Worker 不可达，需要创建新实例

**代码位置**：
- 接管尝试：[`TaskExecutionRunnable#takeOverTaskFromExecutor()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L410-L422)
- RPC 调用：[`PhysicalTaskExecutorClientDelegator#reassignMasterHost()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L107-L159)

### 12.3 内存隔离并发控制

**设计原因**：
- 多个 Master 可能同时检测到 Worker 故障
- 避免分布式锁的性能开销
- 利用 Master 的工作流分配机制实现并发控制

**实现方式**：
- 每个 Master 只处理自己内存中的任务（`workflowRepository.getAll()`）
- 每个工作流实例只由一个 Master 管理（通过 Command 的唯一性保证）
- 任务属于工作流实例，因此任务也只由一个 Master 处理
- 原任务实例被标记为 `NEED_FAULT_TOLERANCE`，不会被重复处理

**代码位置**：
- 内存查询：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L430-L431)
- 原任务标记：[`FailoverTaskInstanceFactory#createTaskInstance()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L65-L67)

### 12.4 任务状态过滤

**设计原因**：
- 只故障转移正在执行的任务
- 已完成或失败的任务不需要转移
- 避免不必要的操作

**实现方式**：
- `getFailoverTaskForWorker()` 只筛选状态为 `DISPATCH` 或 `RUNNING_EXECUTION` 的任务
- 状态为 `SUCCESS`、`FAILURE`、`KILLED`、`PAUSED` 的任务不会被故障转移

**代码位置**：
- 状态过滤：[`FailoverCoordinator#getFailoverTaskForWorker()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L426-L429)

### 12.5 任务实例克隆与标记

**设计原因**：
- 保留原任务实例的历史信息
- 新任务实例可以重新执行
- 通过状态区分原任务和新任务

**实现方式**：
- **原任务实例**：
  - `state = NEED_FAULT_TOLERANCE`
  - `flag = NO`（标记为不可用）
- **新任务实例**：
  - `id = null`（新实例）
  - `state = SUBMITTED_SUCCESS`
  - `host = null`（待重新分发）
  - `varPool = null`（重新初始化）
  - `logPath = null`（重新生成）
  - `executePath = null`（重新生成）

**代码位置**：
- 实例创建：[`FailoverTaskInstanceFactory#createTaskInstance()`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L47-L69)

## 13. 关键代码链接

### 13.1 故障检测

- **ClusterStateMonitors**: [`ClusterStateMonitors.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java)
  - `workerRemoved()`: [第171行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/cluster/ClusterStateMonitors.java#L171-L175)

### 13.2 故障转移处理

- **WorkerFailoverEventHandler**: [`WorkerFailoverEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEventHandler.java)
  - `handle()`: [第35-36行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/system/event/WorkerFailoverEventHandler.java#L35-L36)

- **FailoverCoordinator**: [`FailoverCoordinator.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java)
  - `failoverWorker()`: [第330-355行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L330-L355)
  - `doWorkerFailover()`: [第370-401行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L370-L401)
  - `getFailoverTaskForWorker()`: [第420-457行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/FailoverCoordinator.java#L420-L457)

### 13.3 任务故障转移

- **TaskFailover**: [`TaskFailover.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/TaskFailover.java)
  - `failoverTask()`: [第29-33行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/failover/TaskFailover.java#L29-L33)

- **TaskFailoverLifecycleEventHandler**: [`TaskFailoverLifecycleEventHandler.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailoverLifecycleEventHandler.java)
  - `handle()`: [第32-37行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/lifecycle/handler/TaskFailoverLifecycleEventHandler.java#L32-L37)

### 13.4 任务状态处理

- **TaskRunningStateAction**: [`TaskRunningStateAction.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/TaskRunningStateAction.java)
  - `failoverEventAction()`: [第133-139行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/TaskRunningStateAction.java#L133-L139)

- **AbstractTaskStateAction**: [`AbstractTaskStateAction.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/AbstractTaskStateAction.java)
  - `failoverTask()`: [第225-227行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/statemachine/AbstractTaskStateAction.java#L225-L227)

### 13.5 任务接管与重建

- **TaskExecutionRunnable**: [`TaskExecutionRunnable.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java)
  - `failover()`: [第294-334行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L294-L334)
  - `takeOverTaskFromExecutor()`: [第410-422行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L410-L422)
  - `initializeTaskExecutionContext()`: [第399-414行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java#L399-L414)

### 13.6 客户端调用

- **TaskExecutorClient**: [`TaskExecutorClient.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/TaskExecutorClient.java)
  - `reassignWorkflowInstanceHost()`: [第64-73行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/TaskExecutorClient.java#L64-L73)

- **PhysicalTaskExecutorClientDelegator**: [`PhysicalTaskExecutorClientDelegator.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java)
  - `reassignMasterHost()`: [第107-159行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L107-L159)
  - `dispatch()`: [第65-96行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java#L65-L96)

### 13.7 任务实例工厂

- **FailoverTaskInstanceFactory**: [`FailoverTaskInstanceFactory.java`](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java)
  - `createTaskInstance()`: [第47-69行](../../../../dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/FailoverTaskInstanceFactory.java#L47-L69)

## 14. 总结

Worker 故障容错机制是 DolphinScheduler 高可用性的重要保障。通过延迟确认、试探性接管、内存隔离、状态过滤等设计，实现了高效、可靠的故障转移。

**核心流程**：
1. **故障检测**：ZooKeeper 检测节点移除，触发故障转移流程
2. **延迟确认**：30 秒延迟避免误触发（网络抖动）
3. **任务查询**：从 Master 内存中查询需要故障转移的任务
4. **接管尝试**：尝试通过 RPC 调用 Worker，确认 Worker 是否真的故障
5. **实例创建**：如果接管失败，创建新的故障转移任务实例
6. **重新分发**：通过负载均衡器选择新的 Worker，重新分发任务

**关键设计点**：
- **延迟确认机制**：避免网络抖动导致的误触发
- **试探性接管机制**：确认 Worker 是否真的故障，避免不必要的任务重建
- **内存隔离并发控制**：通过内存隔离实现并发控制，而不是分布式锁
- **任务状态过滤**：只故障转移正在执行的任务
- **任务实例克隆与标记**：保留原任务实例的历史信息，新任务实例可以重新执行

通过这些机制，DolphinScheduler 能够有效处理 Worker 故障，确保任务的最终一致性和系统的高可用性。

