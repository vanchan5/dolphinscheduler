# JDK 动态代理架构图

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端代码层                               │
│  TriggerWorkflowExecutorDelegate                                │
│                                                                 │
│  IWorkflowControlClient client = Clients                        │
│      .withService(IWorkflowControlClient.class)                 │
│      .withHost("192.168.1.100:5678");                          │
│                                                                 │
│  client.manualTriggerWorkflow(request);  ←─── 像调用本地方法    │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ 调用
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      JDK 动态代理层                               │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 代理对象 ($Proxy0)                                        │ │
│  │ - 实现了 IWorkflowControlClient 接口                     │ │
│  │ - 所有方法都调用 InvocationHandler.invoke()              │ │
│  └──────────────────────────────────────────────────────────┘ │
│                            │                                    │
│                            │ 拦截                               │
│                            ▼                                    │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ ClientInvocationHandler                                  │ │
│  │ - 检查 @RpcMethod 注解                                   │ │
│  │ - 创建/获取 MethodInvoker                                │ │
│  │ - 调用 MethodInvoker.invoke()                           │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ 转换
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RPC 调用层                                  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ SyncClientMethodInvoker                                  │ │
│  │ - 生成方法标识符                                         │ │
│  │ - 序列化参数                                             │ │
│  │ - 构建 Transporter                                       │ │
│  │ - 调用 NettyRemotingClient.sendSync()                   │ │
│  └──────────────────────────────────────────────────────────┘ │
│                            │                                    │
│                            │ 发送                               │
│                            ▼                                    │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ NettyRemotingClient                                      │ │
│  │ - 创建 ResponseFuture                                    │ │
│  │ - 通过 Netty Channel 发送请求                            │ │
│  │ - 阻塞等待响应                                           │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ 网络传输
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                        服务端                                    │
│  - 接收请求                                                     │
│  - 根据 methodIdentifier 查找方法                              │
│  - 执行方法调用                                                 │
│  - 返回响应                                                     │
└─────────────────────────────────────────────────────────────────┘
```

## 二、类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                    Clients (静态工厂)                         │
│  + withService(Class<T>): JdkDynamicRpcClientProxyBuilder   │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 使用
                            ▼
┌─────────────────────────────────────────────────────────────┐
│          JdkDynamicRpcClientProxyFactory                     │
│  - nettyRemotingClient: NettyRemotingClient                  │
│  - proxyClientCache: LoadingCache                            │
│  + getProxyClient(serverHost, interface): T                  │
│  - newProxyClient(): T (使用 Proxy.newProxyInstance)         │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 创建
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              ClientInvocationHandler                         │
│  implements InvocationHandler                                │
│  - serverHost: Host                                          │
│  - nettyRemotingClient: NettyRemotingClient                  │
│  - methodInvokerMap: Map<String, ClientMethodInvoker>       │
│  + invoke(proxy, method, args): Object                      │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 使用
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              SyncClientMethodInvoker                         │
│  extends AbstractClientMethodInvoker                         │
│  - serverHost: Host                                          │
│  - methodIdentifier: String                                  │
│  - nettyRemotingClient: NettyRemotingClient                  │
│  + invoke(proxy, method, args): Object                      │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 调用
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              NettyRemotingClient                              │
│  - channels: Map<Host, Channel>                              │
│  + sendSync(syncRequestDto): IRpcResponse                    │
│  - doSendSync(): IRpcResponse                                │
└─────────────────────────────────────────────────────────────┘
```

## 三、代理对象结构

```
代理对象 ($Proxy0)
│
├── 实现接口: IWorkflowControlClient
│
├── 字段:
│   └── InvocationHandler h  ←─── ClientInvocationHandler 实例
│
└── 方法:
    ├── manualTriggerWorkflow(request)
    │   └── return (WorkflowManualTriggerResponse) h.invoke(
    │           this,
    │           method,
    │           new Object[]{request}
    │       );
    │
    ├── backfillTriggerWorkflow(request)
    │   └── return h.invoke(...);
    │
    └── ... (其他接口方法)
        └── return h.invoke(...);
```

## 四、缓存机制

```
┌─────────────────────────────────────────────────────────────┐
│          proxyClientCache (LoadingCache)                     │
│  Key: serverHost (String)                                    │
│  Value: Map<String, Object>                                  │
│    └── Key: interfaceName (String)                          │
│        Value: 代理对象                                        │
│                                                              │
│  示例：                                                       │
│  "192.168.1.100:5678" → {                                   │
│      "IWorkflowControlClient" → $Proxy0 实例,               │
│      "ITaskExecutorClient" → $Proxy1 实例                    │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│      methodInvokerMap (ConcurrentHashMap)                    │
│  Key: method.toGenericString() (String)                     │
│  Value: ClientMethodInvoker                                 │
│                                                              │
│  示例：                                                       │
│  "IWorkflowControlClient#manualTriggerWorkflow(...)"        │
│    → SyncClientMethodInvoker 实例                           │
└─────────────────────────────────────────────────────────────┘
```

## 五、方法调用流程

```
客户端调用
    │
    ▼
代理对象方法
    │
    ▼
ClientInvocationHandler.invoke()
    │
    ├── 检查 @RpcMethod 注解
    │   │
    │   ├── 有注解 → 转换为 RPC 调用
    │   │   │
    │   │   ▼
    │   │   SyncClientMethodInvoker.invoke()
    │   │   │
    │   │   ├── 生成方法标识符
    │   │   ├── 序列化参数
    │   │   ├── 构建 Transporter
    │   │   └── NettyRemotingClient.sendSync()
    │   │
    │   └── 无注解 → 直接调用原方法
    │
    ▼
返回结果
```

## 六、关键设计模式

### 6.1 代理模式（Proxy Pattern）

- **目的**：为其他对象提供一种代理以控制对这个对象的访问
- **实现**：JDK 动态代理
- **优势**：透明地添加额外功能（RPC 调用）

### 6.2 工厂模式（Factory Pattern）

- **目的**：创建对象而不指定具体的类
- **实现**：`Clients`、`JdkDynamicRpcClientProxyFactory`
- **优势**：简化对象创建，统一管理

### 6.3 策略模式（Strategy Pattern）

- **目的**：定义一系列算法，把它们封装起来，并且使它们可互换
- **实现**：`ClientMethodInvoker` 接口，`SyncClientMethodInvoker` 实现
- **优势**：可以支持不同的调用策略（同步、异步等）

### 6.4 缓存模式（Cache Pattern）

- **目的**：提高性能，避免重复创建对象
- **实现**：`LoadingCache`、`ConcurrentHashMap`
- **优势**：减少对象创建开销，提高响应速度
