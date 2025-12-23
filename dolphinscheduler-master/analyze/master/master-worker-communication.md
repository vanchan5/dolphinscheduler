# Master 和 Worker 通信机制详解

## 目录
1. [通信架构概述](#通信架构概述)
2. [双向通信模式](#双向通信模式)
3. [Master -> Worker 通信](#master---worker-通信)
4. [Worker -> Master 通信](#worker---master-通信)
5. [RPC 接口定义](#rpc-接口定义)
6. [通信流程示例](#通信流程示例)
7. [关键技术点](#关键技术点)

---

## 通信架构概述

DolphinScheduler 的 Master 和 Worker 之间采用**双向 RPC 通信**模式，基于 Netty 实现：

```
┌─────────────────┐                    ┌─────────────────┐
│     Master      │                    │     Worker      │
│                 │                    │                 │
│  MasterRpcServer│◄──────────────────►│  WorkerRpcServer│
│  (监听端口 5678) │                    │  (监听端口 1234) │
│                 │                    │                 │
│  - 接收任务事件  │                    │  - 接收任务命令  │
│  - 发送任务命令  │                    │  - 发送任务事件  │
└─────────────────┘                    └─────────────────┘
```

### 核心组件

- **MasterRpcServer**: Master 端的 RPC 服务器，监听来自 Worker 的请求
- **WorkerRpcServer**: Worker 端的 RPC 服务器，监听来自 Master 的请求
- **Clients**: RPC 客户端工厂，用于创建动态代理客户端
- **@RpcService / @RpcMethod**: 注解驱动的 RPC 服务注册

---

## 双向通信模式

### 1. Master -> Worker 通信

**场景**: Master 向 Worker 发送任务控制命令

**接口**: `ITaskInstanceOperator` (定义在 Worker 端)

**主要操作**:
- `dispatchTask()`: 分发任务到 Worker
- `killTask()`: 杀死任务
- `pauseTask()`: 暂停任务
- `takeOverTask()`: 接管任务（故障转移）

### 2. Worker -> Master 通信

**场景**: Worker 向 Master 发送任务执行状态事件

**接口**: `ITaskExecutionEventListener` (定义在 Master 端)

**主要事件**:
- `onTaskInstanceDispatched()`: 任务已分发
- `onTaskInstanceExecutionRunning()`: 任务执行中
- `onTaskInstanceExecutionSuccess()`: 任务执行成功
- `onTaskInstanceExecutionFailed()`: 任务执行失败
- `onTaskInstanceExecutionKilled()`: 任务被杀死
- `onTaskInstanceExecutionPaused()`: 任务已暂停

---

## Master -> Worker 通信

### 1. RPC 服务器启动

**Worker 端** (`WorkerRpcServer`):

```java
@Service
public class WorkerRpcServer extends SpringServerMethodInvokerDiscovery implements Closeable {
    public WorkerRpcServer(WorkerConfig workerConfig) {
        super(NettyServerConfig.builder()
                .serverName("WorkerRpcServer")
                .listenPort(workerConfig.getListenPort())  // 默认 1234
                .build());
    }
}
```

**关键点**:
- 继承 `SpringServerMethodInvokerDiscovery`，自动发现 Spring 容器中的 `@RpcService` 接口实现
- 启动 Netty 服务器，监听 Worker 端口

### 2. RPC 接口定义

**Worker 端接口** (`ITaskInstanceOperator`):

```java
@RpcService
public interface ITaskInstanceOperator {
    @RpcMethod
    TaskInstanceDispatchResponse dispatchTask(TaskInstanceDispatchRequest request);
    
    @RpcMethod
    TaskInstanceKillResponse killTask(TaskInstanceKillRequest request);
    
    @RpcMethod
    TaskInstancePauseResponse pauseTask(TaskInstancePauseRequest request);
    
    @RpcMethod
    TakeOverTaskResponse takeOverTask(TakeOverTaskRequest request);
}
```

### 3. Worker 端实现

**任务分发实现** (`TaskInstanceDispatchOperationFunction`):

```java
@Component
public class TaskInstanceDispatchOperationFunction
        implements ITaskInstanceOperationFunction<TaskInstanceDispatchRequest, TaskInstanceDispatchResponse> {
    
    @Override
    public TaskInstanceDispatchResponse operate(TaskInstanceDispatchRequest request) {
        TaskExecutionContext taskExecutionContext = request.getTaskExecutionContext();
        
        // 1. 检查服务器状态
        if (!ServerLifeCycleManager.isRunning()) {
            return TaskInstanceDispatchResponse.failed(taskInstanceId, "server is not running");
        }
        
        // 2. 创建任务执行器
        WorkerTaskExecutor workerTaskExecutor = workerTaskExecutorFactoryBuilder
                .createWorkerTaskExecutorFactory(taskExecutionContext)
                .createWorkerTaskExecutor();
        
        // 3. 提交到线程池执行
        if (workerTaskExecutorThreadPool.submitWorkerTaskExecutor(workerTaskExecutor)) {
            return TaskInstanceDispatchResponse.success(taskInstanceId);
        } else {
            return TaskInstanceDispatchResponse.failed(taskInstanceId, "WorkerManagerThread is full");
        }
    }
}
```

### 4. Master 端调用

**任务分发器** (`WorkerTaskDispatcher`):

```java
@Component
public class WorkerTaskDispatcher extends BaseTaskDispatcher {
    
    @Override
    protected void doDispatch(ITaskExecutionRunnable taskExecutionRunnable) {
        final TaskExecutionContext taskExecutionContext = taskExecutionRunnable.getTaskExecutionContext();
        final String workerAddress = taskExecutionContext.getHost();
        
        // 使用 Clients 创建动态代理客户端
        final TaskInstanceDispatchResponse response = Clients
                .withService(ITaskInstanceOperator.class)  // 指定 RPC 接口
                .withHost(workerAddress)                    // 指定 Worker 地址
                .dispatchTask(new TaskInstanceDispatchRequest(taskExecutionContext));
        
        if (!response.isDispatchSuccess()) {
            throw new TaskDispatchException("Dispatch task failed: " + response);
        }
    }
}
```

**调用流程**:
```
Master -> Clients.withService() 
      -> 创建动态代理 
      -> NettyRemotingClient.sendSync() 
      -> Worker RPC Server 
      -> TaskInstanceDispatchOperationFunction.operate()
      -> 返回响应
```

---

## Worker -> Master 通信

### 1. RPC 服务器启动

**Master 端** (`MasterRpcServer`):

```java
@Component
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery implements AutoCloseable {
    public MasterRpcServer(MasterConfig masterConfig) {
        super(NettyServerConfig.builder()
                .serverName("MasterRpcServer")
                .listenPort(masterConfig.getListenPort())  // 默认 5678
                .build());
    }
}
```

### 2. RPC 接口定义

**Master 端接口** (`ITaskExecutionEventListener`):

```java
@RpcService
public interface ITaskExecutionEventListener {
    @RpcMethod
    void onTaskInstanceDispatched(TaskExecutionDispatchEvent event);
    
    @RpcMethod
    void onTaskInstanceExecutionRunning(TaskExecutionRunningEvent event);
    
    @RpcMethod
    void onTaskInstanceExecutionSuccess(TaskExecutionSuccessEvent event);
    
    @RpcMethod
    void onTaskInstanceExecutionFailed(TaskExecutionFailedEvent event);
    
    @RpcMethod
    void onTaskInstanceExecutionKilled(TaskExecutionKilledEvent event);
    
    @RpcMethod
    void onTaskInstanceExecutionPaused(TaskExecutionPausedEvent event);
}
```

### 3. Master 端实现

**事件监听器实现** (`TaskExecutionEventListenerImpl`):

```java
@Service
public class TaskExecutionEventListenerImpl implements ITaskExecutionEventListener {
    
    @Override
    public void onTaskInstanceExecutionSuccess(TaskExecutionSuccessEvent event) {
        // 1. 获取任务执行器
        final ITaskExecutionRunnable taskExecutionRunnable = getTaskExecutionRunnable(event);
        
        // 2. 发布生命周期事件
        final TaskSuccessLifecycleEvent lifecycleEvent = TaskSuccessLifecycleEvent.builder()
                .taskExecutionRunnable(taskExecutionRunnable)
                .endTime(new Date(event.getEndTime()))
                .varPool(event.getVarPool())
                .build();
        
        taskExecutionRunnable.getWorkflowEventBus().publish(lifecycleEvent);
        
        // 3. 发送 ACK 确认（非逻辑任务）
        if (!TaskTypeUtils.isLogicTask(taskExecutionRunnable.getTaskDefinition().getTaskType())) {
            Clients
                    .withService(ITaskInstanceExecutionEventAckListener.class)
                    .withHost(event.getTaskInstanceHost())
                    .handleTaskInstanceExecutionSuccessEventAck(
                            TaskInstanceExecutionSuccessEventAck.success(event.getTaskInstanceId()));
        }
    }
    
    // ... 其他事件处理方法
}
```

### 4. Worker 端发送事件

**事件发送器** (`TaskExecutionSuccessEventSender`):

```java
@Component
public class TaskExecutionSuccessEventSender
        implements TaskInstanceExecutionEventSender<TaskExecutionSuccessEvent> {
    
    @Override
    public void sendEvent(TaskExecutionSuccessEvent message) {
        // 使用 Clients 创建动态代理，调用 Master 的 RPC 接口
        Clients
                .withService(ITaskExecutionEventListener.class)  // Master 端接口
                .withHost(message.getWorkflowInstanceHost())     // Master 地址
                .onTaskInstanceExecutionSuccess(message);
    }
    
    @Override
    public TaskExecutionSuccessEvent buildEvent(TaskExecutionContext taskExecutionContext) {
        return TaskExecutionSuccessEvent.builder()
                .workflowInstanceId(taskExecutionContext.getWorkflowInstanceId())
                .taskInstanceId(taskExecutionContext.getTaskInstanceId())
                .appIds(taskExecutionContext.getAppIds())
                .processId(taskExecutionContext.getProcessId())
                .workflowInstanceHost(taskExecutionContext.getWorkflowInstanceHost())
                .taskInstanceHost(taskExecutionContext.getHost())
                .endTime(taskExecutionContext.getEndTime())
                .varPool(taskExecutionContext.getVarPool())
                .build();
    }
}
```

**消息发送管理器** (`WorkerMessageSender`):

```java
@Component
public class WorkerMessageSender {
    
    public void sendMessageWithRetry(TaskExecutionContext taskExecutionContext,
                                     ITaskExecutionEvent.TaskInstanceExecutionEventType eventType) {
        // 1. 获取对应的事件发送器
        TaskInstanceExecutionEventSender messageSender = messageSenderMap.get(eventType);
        
        // 2. 构建事件对象
        ITaskExecutionEvent event = messageSender.buildEvent(taskExecutionContext);
        
        // 3. 添加到重试队列
        messageRetryRunner.addRetryMessage(taskExecutionContext.getTaskInstanceId(), event);
        
        // 4. 发送事件
        messageSender.sendEvent(event);
    }
}
```

**调用流程**:
```
Worker TaskExecutor 
    -> sendTaskResult() 
    -> WorkerMessageSender.sendMessageWithRetry() 
    -> TaskExecutionSuccessEventSender.sendEvent() 
    -> Clients.withService() 
    -> NettyRemotingClient.sendSync() 
    -> Master RPC Server 
    -> TaskExecutionEventListenerImpl.onTaskInstanceExecutionSuccess()
    -> 发布事件到 WorkflowEventBus
```

---

## RPC 接口定义

### 接口注解

**@RpcService**: 标记 RPC 服务接口
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcService {
}
```

**@RpcMethod**: 标记 RPC 方法
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcMethod {
    long timeout() default -1;  // 超时时间（毫秒），-1 表示使用默认值
}
```

### 服务自动发现

**SpringServerMethodInvokerDiscovery**:
- 继承 `RpcServer` 和 `BeanPostProcessor`
- 在 Spring Bean 初始化后，自动扫描 `@RpcService` 接口的实现类
- 将实现类注册为 RPC 服务方法调用器

```java
public class SpringServerMethodInvokerDiscovery extends RpcServer implements BeanPostProcessor {
    
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        registerServerMethodInvokerProvider(bean);  // 自动注册
        return bean;
    }
}
```

### 客户端动态代理

**Clients 工厂类**:

```java
public class Clients {
    private static final JdkDynamicRpcClientProxyFactory factory = ...;
    
    public static <T> JdkDynamicRpcClientProxyBuilder<T> withService(Class<T> serviceClazz) {
        return new JdkDynamicRpcClientProxyBuilder<>(serviceClazz);
    }
    
    public static class JdkDynamicRpcClientProxyBuilder<T> {
        public T withHost(String serviceHost) {
            return factory.getProxyClient(serviceHost, serviceClazz);
        }
    }
}
```

**使用示例**:
```java
// 创建动态代理客户端
ITaskInstanceOperator client = Clients
        .withService(ITaskInstanceOperator.class)
        .withHost("192.168.1.100:1234");

// 调用 RPC 方法（就像调用本地方法一样）
TaskInstanceDispatchResponse response = client.dispatchTask(request);
```

---

## 通信流程示例

### 示例 1: Master 分发任务到 Worker

```
1. Master 选择 Worker
   └─> WorkerTaskDispatcher.getTaskInstanceDispatchHost()
       └─> WorkerLoadBalancer.select(workerGroup)

2. Master 调用 Worker RPC
   └─> Clients.withService(ITaskInstanceOperator.class)
       .withHost(workerAddress)
       .dispatchTask(request)
       └─> NettyRemotingClient.sendSync()
           └─> 通过 Netty 发送请求到 Worker:1234

3. Worker 接收请求
   └─> WorkerRpcServer (Netty Server)
       └─> JdkDynamicServerHandler.channelRead()
           └─> TaskInstanceDispatchOperationFunction.operate()
               └─> 创建 WorkerTaskExecutor
               └─> 提交到线程池执行
               └─> 返回 TaskInstanceDispatchResponse

4. Master 接收响应
   └─> ResponseFuture.putResponse()
       └─> 唤醒等待线程
       └─> 返回响应结果
```

### 示例 2: Worker 发送任务成功事件到 Master

```
1. Worker 任务执行完成
   └─> WorkerTaskExecutor.sendTaskResult()
       └─> WorkerMessageSender.sendMessageWithRetry()
           └─> TaskExecutionSuccessEventSender.sendEvent()

2. Worker 调用 Master RPC
   └─> Clients.withService(ITaskExecutionEventListener.class)
       .withHost(masterAddress)
       .onTaskInstanceExecutionSuccess(event)
       └─> NettyRemotingClient.sendSync()
           └─> 通过 Netty 发送请求到 Master:5678

3. Master 接收事件
   └─> MasterRpcServer (Netty Server)
       └─> JdkDynamicServerHandler.channelRead()
           └─> TaskExecutionEventListenerImpl.onTaskInstanceExecutionSuccess()
               └─> 发布 TaskSuccessLifecycleEvent 到 WorkflowEventBus
               └─> 发送 ACK 确认给 Worker

4. Worker 接收 ACK
   └─> MessageRetryRunner 收到 ACK，停止重试
```

---

## 关键技术点

### 1. 动态代理

- 使用 JDK 动态代理创建 RPC 客户端
- 接口方法调用被拦截，转换为 Netty RPC 调用
- 对用户透明，就像调用本地方法

### 2. 同步调用

- 使用 `CountDownLatch` 实现同步等待
- `ResponseFuture` 管理请求-响应映射
- 支持超时控制

### 3. 服务自动发现

- 基于 Spring `BeanPostProcessor` 机制
- 自动扫描 `@RpcService` 接口的实现
- 无需手动注册服务

### 4. 消息重试

- Worker 发送事件失败时自动重试
- 收到 Master 的 ACK 后停止重试
- 保证消息可靠传递

### 5. 连接管理

- 客户端缓存 Channel，复用连接
- 心跳保活机制
- 连接断开自动清理

### 6. 负载均衡

- Master 使用 `WorkerLoadBalancer` 选择 Worker
- 支持多种负载均衡策略（轮询、随机等）

### 7. 故障转移

- Worker 故障时，Master 可以接管任务
- 通过 `takeOverTask()` 接口实现

### 8. 事件驱动

- Worker 通过事件通知 Master 任务状态
- Master 通过事件总线处理任务生命周期
- 解耦任务执行和状态管理

---

## 总结

DolphinScheduler 的 Master 和 Worker 通信具有以下特点：

1. **双向通信**: Master 和 Worker 都可以作为客户端和服务器
2. **注解驱动**: 使用 `@RpcService` 和 `@RpcMethod` 简化服务定义
3. **动态代理**: 客户端使用动态代理，调用方式简单
4. **自动发现**: 基于 Spring 自动发现和注册服务
5. **可靠传输**: 支持消息重试和 ACK 确认
6. **高性能**: 基于 Netty 异步非阻塞 I/O

### 通信端口

- **Master RPC Server**: 默认 5678
- **Worker RPC Server**: 默认 1234（可配置）

### 主要接口

**Master -> Worker**:
- `ITaskInstanceOperator`: 任务控制接口

**Worker -> Master**:
- `ITaskExecutionEventListener`: 任务事件监听接口
- `ITaskInstanceExecutionEventAckListener`: 事件 ACK 确认接口

---

## 参考代码

- `dolphinscheduler-extract-base`: RPC 框架基础实现
- `dolphinscheduler-extract-master`: Master 端 RPC 接口定义
- `dolphinscheduler-extract-worker`: Worker 端 RPC 接口定义
- `dolphinscheduler-master`: Master 端 RPC 实现
- `dolphinscheduler-worker`: Worker 端 RPC 实现

