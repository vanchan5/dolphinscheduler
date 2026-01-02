# RPC Server 初始化流程详解

## 问题

`MasterRpcServer` 的构造函数中，`super(NettyServerConfig.builder()...)` 是如何初始化的？

```java
@Component
@Slf4j
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery implements AutoCloseable {
    public MasterRpcServer(MasterConfig masterConfig) {
        super(NettyServerConfig.builder()
                .serverName("MasterRpcServer")
                .listenPort(masterConfig.getListenPort())
                .build());
    }
}
```

---

## 初始化流程

### 1. 类继承关系

```
MasterRpcServer
    ↓ extends
SpringServerMethodInvokerDiscovery
    ↓ extends
RpcServer
    ↓ contains
NettyRemotingServer
```

### 2. 构造函数调用链

#### 步骤 1: MasterRpcServer 构造函数

```java
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery {
    public MasterRpcServer(MasterConfig masterConfig) {
        // 构建 NettyServerConfig
        NettyServerConfig config = NettyServerConfig.builder()
                .serverName("MasterRpcServer")
                .listenPort(masterConfig.getListenPort())  // 默认 5678
                .build();
        
        // 调用父类构造函数
        super(config);
    }
}
```

**此时**:
- 创建了 `NettyServerConfig` 对象
- 配置了服务器名称和监听端口
- 其他配置使用默认值（如 `soBacklog=1024`, `workerThread=CPU*2` 等）

#### 步骤 2: SpringServerMethodInvokerDiscovery 构造函数

```java
public class SpringServerMethodInvokerDiscovery extends RpcServer implements BeanPostProcessor {
    public SpringServerMethodInvokerDiscovery(NettyServerConfig nettyServerConfig) {
        // 直接传递给父类 RpcServer
        super(nettyServerConfig);
    }
}
```

**此时**:
- 只是简单地将配置传递给父类
- 还没有创建 Netty 服务器

#### 步骤 3: RpcServer 构造函数（关键步骤）

```java
public class RpcServer implements ServerMethodInvokerRegistry, AutoCloseable {
    private final NettyRemotingServer nettyRemotingServer;
    
    public RpcServer(NettyServerConfig nettyServerConfig) {
        // 通过工厂创建 NettyRemotingServer
        this.nettyRemotingServer = NettyRemotingServerFactory.buildNettyRemotingServer(nettyServerConfig);
    }
}
```

**此时**:
- 调用工厂方法创建 `NettyRemotingServer`
- 但**还没有启动**服务器（只是创建对象）

#### 步骤 4: NettyRemotingServerFactory 工厂方法

```java
@UtilityClass
class NettyRemotingServerFactory {
    NettyRemotingServer buildNettyRemotingServer(NettyServerConfig nettyServerConfig) {
        return new NettyRemotingServer(nettyServerConfig);
    }
}
```

**此时**:
- 创建 `NettyRemotingServer` 实例
- 传入配置对象

#### 步骤 5: NettyRemotingServer 构造函数（核心初始化）

```java
class NettyRemotingServer {
    private final ServerBootstrap serverBootstrap = new ServerBootstrap();
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workGroup;
    private final ExecutorService methodInvokerExecutor;
    private final JdkDynamicServerHandler channelHandler;
    private final NettyServerConfig serverConfig;
    
    NettyRemotingServer(final NettyServerConfig serverConfig) {
        // 1. 保存配置
        this.serverConfig = serverConfig;
        this.serverName = serverConfig.getServerName();
        
        // 2. 创建方法调用执行器（线程池）
        this.methodInvokerExecutor = ThreadUtils.newDaemonFixedThreadExecutor(
                serverName + "MethodInvoker-%d", 
                Runtime.getRuntime().availableProcessors() * 2 + 1);
        
        // 3. 创建 Channel Handler
        this.channelHandler = new JdkDynamicServerHandler(methodInvokerExecutor);
        
        // 4. 创建线程工厂
        ThreadFactory bossThreadFactory = ThreadUtils.newDaemonThreadFactory(
                serverName + "BossThread-%d");
        ThreadFactory workerThreadFactory = ThreadUtils.newDaemonThreadFactory(
                serverName + "WorkerThread-%d");
        
        // 5. 创建 EventLoopGroup（根据系统选择 Epoll 或 NIO）
        if (Epoll.isAvailable()) {
            // Linux 系统使用 Epoll
            this.bossGroup = new EpollEventLoopGroup(1, bossThreadFactory);
            this.workGroup = new EpollEventLoopGroup(
                    serverConfig.getWorkerThread(), workerThreadFactory);
        } else {
            // 其他系统使用 NIO
            this.bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
            this.workGroup = new NioEventLoopGroup(
                    serverConfig.getWorkerThread(), workerThreadFactory);
        }
        
        // 注意：此时还没有启动服务器，只是初始化了组件
    }
}
```

**此时完成的工作**:
1. ✅ 创建了 `ServerBootstrap` 对象
2. ✅ 创建了方法调用线程池（`methodInvokerExecutor`）
3. ✅ 创建了 `JdkDynamicServerHandler`
4. ✅ 创建了 `EventLoopGroup`（Boss 和 Worker）
5. ❌ **还没有配置 ServerBootstrap**
6. ❌ **还没有绑定端口**
7. ❌ **服务器还没有启动**

---

## 服务器启动流程

### 何时启动？

服务器不是在构造函数中启动的，而是在 Spring 容器初始化完成后，通过 `@PostConstruct` 方法启动。

#### Master 端启动

```java
@SpringBootApplication
public class MasterServer {
    @Autowired
    private MasterRpcServer masterRPCServer;
    
    @PostConstruct
    public void initialized() {
        // 在 Spring Bean 初始化完成后，手动启动 RPC 服务器
        this.masterRPCServer.start();  // 调用 RpcServer.start()
    }
}
```

#### Worker 端启动

```java
@SpringBootApplication
public class WorkerServer {
    @Autowired
    private WorkerRpcServer workerRpcServer;
    
    @PostConstruct
    public void run() {
        // 启动 RPC 服务器
        this.workerRpcServer.start();  // 调用 RpcServer.start()
    }
}
```

### 启动过程

#### 步骤 1: RpcServer.start()

```java
public class RpcServer {
    public void start() {
        // 委托给 NettyRemotingServer
        nettyRemotingServer.start();
    }
}
```

#### 步骤 2: NettyRemotingServer.start()（实际启动）

```java
class NettyRemotingServer {
    void start() {
        // 使用 CAS 确保只启动一次
        if (isStarted.compareAndSet(false, true)) {
            // 1. 配置 ServerBootstrap
            this.serverBootstrap
                    .group(this.bossGroup, this.workGroup)  // 设置线程组
                    .channel(NettyUtils.getServerSocketChannelClass())  // 选择 Channel 类型
                    .option(ChannelOption.SO_REUSEADDR, true)  // 地址重用
                    .option(ChannelOption.SO_BACKLOG, serverConfig.getSoBacklog())  // 连接队列
                    .childOption(ChannelOption.SO_KEEPALIVE, serverConfig.isSoKeepalive())  // 保活
                    .childOption(ChannelOption.TCP_NODELAY, serverConfig.isTcpNoDelay())  // 禁用 Nagle
                    .childOption(ChannelOption.SO_SNDBUF, serverConfig.getSendBufferSize())  // 发送缓冲区
                    .childOption(ChannelOption.SO_RCVBUF, serverConfig.getReceiveBufferSize())  // 接收缓冲区
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            initNettyChannel(ch);
                        }
                    });
            
            // 2. 绑定端口
            ChannelFuture future;
            try {
                future = serverBootstrap.bind(serverConfig.getListenPort()).sync();
            } catch (Exception e) {
                throw new RemoteException("bind fail", e);
            }
            
            // 3. 检查绑定结果
            if (future.isSuccess()) {
                log.info("{} bind success at port: {}", 
                        serverConfig.getServerName(), 
                        serverConfig.getListenPort());
            } else {
                throw new RemoteException("bind fail", future.cause());
            }
        }
    }
    
    private void initNettyChannel(SocketChannel ch) {
        // 配置 Channel Pipeline
        ch.pipeline()
                .addLast("encoder", new TransporterEncoder())      // 编码器
                .addLast("decoder", new TransporterDecoder())      // 解码器
                .addLast("server-idle-handle", 
                        new IdleStateHandler(
                                serverConfig.getConnectionIdleTime(), 0, 0, 
                                TimeUnit.MILLISECONDS))            // 空闲检测
                .addLast("handler", channelHandler);               // 业务处理器
    }
}
```

**启动时完成的工作**:
1. ✅ 配置 `ServerBootstrap` 的所有选项
2. ✅ 设置 Channel Pipeline（编码器、解码器、空闲处理器、业务处理器）
3. ✅ 绑定监听端口（Master: 5678, Worker: 1234）
4. ✅ 服务器开始监听连接

---

## 完整时序图

```
Spring 容器启动
    │
    ├─> 创建 MasterConfig Bean
    │
    ├─> 创建 MasterRpcServer Bean
    │   │
    │   ├─> MasterRpcServer(MasterConfig)
    │   │   │
    │   │   ├─> NettyServerConfig.builder()...
    │   │   │
    │   │   └─> super(config)
    │   │       │
    │   │       ├─> SpringServerMethodInvokerDiscovery(config)
    │   │       │   │
    │   │       │   └─> super(config)
    │   │       │       │
    │   │       │       ├─> RpcServer(config)
    │   │       │       │   │
    │   │       │       │   └─> NettyRemotingServerFactory.buildNettyRemotingServer(config)
    │   │       │       │       │
    │   │       │       │       └─> new NettyRemotingServer(config)
    │   │       │       │           │
    │   │       │       │           ├─> 创建 methodInvokerExecutor（线程池）
    │   │       │       │           ├─> 创建 JdkDynamicServerHandler
    │   │       │       │           ├─> 创建 bossGroup（1 个线程）
    │   │       │       │           └─> 创建 workGroup（N 个线程）
    │   │       │       │
    │   │       │       └─> 保存 nettyRemotingServer 引用
    │   │       │
    │   │       └─> 返回 MasterRpcServer 实例（未启动）
    │   │
    │   └─> Bean 创建完成
    │
    ├─> Spring 容器初始化完成
    │
    └─> @PostConstruct 方法执行
        │
        └─> MasterServer.initialized()
            │
            └─> masterRPCServer.start()
                │
                └─> RpcServer.start()
                    │
                    └─> NettyRemotingServer.start()
                        │
                        ├─> 配置 ServerBootstrap
                        ├─> 设置 Channel Pipeline
                        ├─> 绑定端口（5678）
                        └─> 服务器开始监听 ✅
```

---

## 关键点总结

### 1. 构造函数阶段（对象创建）

**只创建对象，不启动服务**:
- 创建配置对象
- 创建线程池
- 创建 EventLoopGroup
- 创建 Handler
- **但不绑定端口，不启动监听**

### 2. 启动阶段（@PostConstruct）

**实际启动服务器**:
- 配置 ServerBootstrap
- 设置 Channel Pipeline
- 绑定端口
- 开始监听连接

### 3. 服务注册阶段（BeanPostProcessor）

**自动发现和注册 RPC 服务**:

```java
public class SpringServerMethodInvokerDiscovery implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 每个 Bean 初始化后，检查是否实现了 @RpcService 接口
        registerServerMethodInvokerProvider(bean);
        return bean;
    }
}
```

**注册流程**:
```java
public void registerServerMethodInvokerProvider(Object bean) {
    // 1. 遍历 Bean 实现的所有接口
    for (Class<?> anInterface : bean.getClass().getInterfaces()) {
        // 2. 检查接口是否有 @RpcService 注解
        if (anInterface.getAnnotation(RpcService.class) == null) {
            continue;
        }
        // 3. 遍历接口的所有方法
        for (Method method : anInterface.getDeclaredMethods()) {
            // 4. 检查方法是否有 @RpcMethod 注解
            RpcMethod rpcMethod = method.getAnnotation(RpcMethod.class);
            if (rpcMethod == null) {
                continue;
            }
            // 5. 创建 ServerMethodInvoker 并注册
            ServerMethodInvoker invoker = new ServerMethodInvokerImpl(bean, method);
            nettyRemotingServer.registerMethodInvoker(invoker);
        }
    }
}
```

**示例**:
```java
@Service
public class TaskExecutionEventListenerImpl implements ITaskExecutionEventListener {
    // 当这个 Bean 初始化后，SpringServerMethodInvokerDiscovery 会：
    // 1. 发现 ITaskExecutionEventListener 接口有 @RpcService 注解
    // 2. 遍历接口方法，发现所有方法都有 @RpcMethod 注解
    // 3. 为每个方法创建 ServerMethodInvoker
    // 4. 注册到 NettyRemotingServer
}
```

---

## 配置参数说明

### NettyServerConfig 默认值

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `serverName` | - | 服务器名称（必须指定） |
| `listenPort` | - | 监听端口（必须指定） |
| `soBacklog` | 1024 | 连接队列大小 |
| `tcpNoDelay` | true | 禁用 Nagle 算法 |
| `soKeepalive` | true | TCP 保活 |
| `sendBufferSize` | 65535 | 发送缓冲区大小（字节） |
| `receiveBufferSize` | 65535 | 接收缓冲区大小（字节） |
| `workerThread` | CPU * 2 | Worker 线程数 |
| `connectionIdleTime` | 60000ms | 连接空闲超时时间 |

### 线程配置

- **Boss Group**: 1 个线程（接受连接）
- **Worker Group**: `CPU 核心数 * 2` 个线程（处理 I/O）
- **Method Invoker Executor**: `CPU 核心数 * 2 + 1` 个线程（执行业务逻辑）

---

## 总结

1. **构造函数阶段**:
   - 创建所有必要的对象（线程池、EventLoopGroup、Handler）
   - **不启动服务器**

2. **启动阶段** (`@PostConstruct`):
   - 配置 ServerBootstrap
   - 绑定端口
   - **开始监听连接**

3. **服务注册阶段** (`BeanPostProcessor`):
   - 自动扫描 Spring 容器中的 Bean
   - 发现 `@RpcService` 接口的实现
   - 注册 RPC 方法到服务器

这种设计的好处：
- **延迟启动**: 只有在 Spring 容器完全初始化后才启动服务器
- **自动发现**: 无需手动注册服务，通过注解自动发现
- **解耦**: 对象创建和服务器启动分离

