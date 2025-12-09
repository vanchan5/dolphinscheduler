# DolphinScheduler Extract Base 模块中的 Netty 学习笔记

## 目录
1. [整体架构概述](#整体架构概述)
2. [Netty 服务器实现](#netty-服务器实现)
3. [Netty 客户端实现](#netty-客户端实现)
4. [编解码器](#编解码器)
5. [配置管理](#配置管理)
6. [工具类](#工具类)
7. [关键设计模式和技术点](#关键设计模式和技术点)

---

## 整体架构概述

`dolphinscheduler-extract-base` 模块基于 Netty 实现了一个完整的 RPC 通信框架，用于 DolphinScheduler 各个组件之间的远程调用。

### 核心组件

- **NettyRemotingServer**: Netty 服务器实现，负责接收和处理客户端请求
- **NettyRemotingClient**: Netty 客户端实现，负责向服务器发送请求并接收响应
- **TransporterEncoder/TransporterDecoder**: 自定义协议编解码器
- **NettyClientHandler/JdkDynamicServerHandler**: 客户端和服务端的 ChannelHandler

### 依赖

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
</dependency>
```

---

## Netty 服务器实现

### NettyRemotingServer 类

**位置**: `org.apache.dolphinscheduler.extract.base.server.NettyRemotingServer`

#### 核心特性

1. **EventLoopGroup 选择**
   - 优先使用 Epoll（Linux 系统），否则使用 NIO
   - Boss Group: 1 个线程（负责接受连接）
   - Worker Group: 可配置线程数（默认 CPU 核心数 * 2）

```java
if (Epoll.isAvailable()) {
    this.bossGroup = new EpollEventLoopGroup(1, bossThreadFactory);
    this.workGroup = new EpollEventLoopGroup(serverConfig.getWorkerThread(), workerThreadFactory);
} else {
    this.bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
    this.workGroup = new NioEventLoopGroup(serverConfig.getWorkerThread(), workerThreadFactory);
}
```

2. **ServerBootstrap 配置**
   - `SO_REUSEADDR`: 允许地址重用
   - `SO_BACKLOG`: 连接队列大小（默认 1024）
   - `SO_KEEPALIVE`: 保持连接活跃
   - `TCP_NODELAY`: 禁用 Nagle 算法，减少延迟
   - 发送/接收缓冲区大小：65535 字节

3. **Channel Pipeline 配置**
   ```
   encoder -> decoder -> idle-handler -> handler
   ```
   - **TransporterEncoder**: 将 Transporter 对象编码为字节流
   - **TransporterDecoder**: 将字节流解码为 Transporter 对象
   - **IdleStateHandler**: 检测连接空闲时间（默认 60 秒）
   - **JdkDynamicServerHandler**: 处理业务逻辑

4. **方法调用执行器**
   - 使用独立的线程池执行方法调用
   - 线程数：`CPU 核心数 * 2 + 1`
   - 避免阻塞 Netty 的 EventLoop 线程

#### 启动流程

```java
void start() {
    if (isStarted.compareAndSet(false, true)) {
        // 1. 配置 ServerBootstrap
        this.serverBootstrap
            .group(this.bossGroup, this.workGroup)
            .channel(NettyUtils.getServerSocketChannelClass())
            .option(ChannelOption.SO_REUSEADDR, true)
            // ... 其他配置
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    initNettyChannel(ch);
                }
            });
        
        // 2. 绑定端口
        ChannelFuture future = serverBootstrap.bind(serverConfig.getListenPort()).sync();
        
        // 3. 检查绑定结果
        if (future.isSuccess()) {
            log.info("{} bind success at port: {}", serverConfig.getServerName(), serverConfig.getListenPort());
        }
    }
}
```

### JdkDynamicServerHandler 类

**位置**: `org.apache.dolphinscheduler.extract.base.server.JdkDynamicServerHandler`

#### 核心功能

1. **方法调用注册**
   - 维护 `methodInvokerMap`，存储方法标识符到 `ServerMethodInvoker` 的映射
   - 支持动态注册方法调用器

2. **消息处理流程**
   ```java
   channelRead() -> processReceived() -> methodInvokeExecutor.execute()
   ```
   - 接收 Transporter 消息
   - 检查是否为心跳消息
   - 查找对应的 MethodInvoker
   - 在线程池中异步执行方法调用
   - 序列化响应并发送回客户端

3. **心跳处理**
   - 识别 `HeartBeatTransporter.METHOD_IDENTIFY`
   - 心跳消息不进行方法调用，直接返回

4. **异常处理**
   - 线程池满时拒绝执行，返回错误响应
   - 捕获方法调用异常，返回失败响应

5. **连接管理**
   - `userEventTriggered`: 处理 IdleStateEvent，超时关闭连接
   - `channelWritabilityChanged`: 根据写缓冲区水位自动调整读取

---

## Netty 客户端实现

### NettyRemotingClient 类

**位置**: `org.apache.dolphinscheduler.extract.base.client.NettyRemotingClient`

#### 核心特性

1. **EventLoopGroup**
   - 只使用 Worker Group（客户端不需要 Boss Group）
   - 线程数可配置（默认 CPU 核心数 * 2）

2. **Channel 管理**
   - 使用 `ConcurrentHashMap<Host, Channel>` 缓存连接
   - 使用 `ReentrantLock` 保证线程安全的 Channel 创建
   - 支持 Channel 复用，避免频繁创建连接

3. **同步请求发送**
   ```java
   public IRpcResponse sendSync(SyncRequestDto syncRequestDto)
   ```
   - 获取或创建 Channel
   - 创建 ResponseFuture 并注册
   - 发送请求（writeAndFlush）
   - 等待响应（CountDownLatch）
   - 处理超时和异常

4. **Channel 创建流程**
   ```java
   Channel getOrCreateChannel(Host host) {
       // 1. 检查缓存中是否有活跃的 Channel
       Channel channel = channels.get(host);
       if (channel != null && channel.isActive()) {
           return channel;
       }
       
       // 2. 双重检查锁定创建新 Channel
       synchronized (channelsLock) {
           channel = createChannel(host);
           channels.put(host, channel);
       }
   }
   ```

5. **Bootstrap 配置**
   - `SO_KEEPALIVE`: 保持连接
   - `TCP_NODELAY`: 禁用延迟
   - `CONNECT_TIMEOUT_MILLIS`: 连接超时（默认 3000ms）

### NettyClientHandler 类

**位置**: `org.apache.dolphinscheduler.extract.base.client.NettyClientHandler`

#### 核心功能

1. **响应处理**
   ```java
   channelRead() -> processReceived()
   ```
   - 根据 `opaque`（请求 ID）查找对应的 ResponseFuture
   - 反序列化响应
   - 唤醒等待的线程

2. **心跳发送**
   ```java
   userEventTriggered() -> 发送 HeartBeatTransporter
   ```
   - 使用 IdleStateHandler 触发写空闲事件
   - 定期发送心跳保持连接活跃（默认 10 秒）

3. **连接管理**
   - `channelInactive`: Channel 断开时清理缓存
   - `exceptionCaught`: 异常时关闭 Channel

### ResponseFuture 类

**位置**: `org.apache.dolphinscheduler.extract.base.future.ResponseFuture`

#### 同步等待机制

```java
public class ResponseFuture {
    private final CountDownLatch latch = new CountDownLatch(1);
    private static final ConcurrentHashMap<Long, ResponseFuture> FUTURE_TABLE = new ConcurrentHashMap<>();
    
    // 等待响应
    public IRpcResponse waitResponse() throws InterruptedException {
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            log.warn("Wait response timeout");
        }
        return this.iRpcResponse;
    }
    
    // 设置响应并唤醒等待线程
    public void putResponse(final IRpcResponse iRpcResponse) {
        this.iRpcResponse = iRpcResponse;
        this.latch.countDown();
        FUTURE_TABLE.remove(opaque);
    }
}
```

**关键点**:
- 使用 `CountDownLatch` 实现同步等待
- 使用 `ConcurrentHashMap` 存储所有未完成的 Future
- 通过 `opaque`（请求 ID）关联请求和响应

---

## 编解码器

### 协议格式

```
+--------+--------+--------+--------+--------+--------+
| MAGIC  | VERSION| HEADER | HEADER |  BODY  |  BODY  |
|  (1B)  |  (1B)  | LENGTH |  (N)   | LENGTH |  (M)   |
|        |        |  (4B)  |        |  (4B)  |        |
+--------+--------+--------+--------+--------+--------+
```

- **MAGIC**: 魔数，固定为 `0xbabe`
- **VERSION**: 协议版本，当前为 `0`
- **HEADER LENGTH**: Header 长度（4 字节）
- **HEADER**: 序列化后的 TransporterHeader（包含 opaque、methodIdentifier 等）
- **BODY LENGTH**: Body 长度（4 字节）
- **BODY**: 序列化后的请求/响应数据

### TransporterEncoder

**位置**: `org.apache.dolphinscheduler.extract.base.protocal.TransporterEncoder`

```java
public class TransporterEncoder extends MessageToByteEncoder<Transporter> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Transporter transporter, ByteBuf out) {
        out.writeByte(Transporter.MAGIC);           // 1 byte
        out.writeByte(Transporter.VERSION);         // 1 byte
        
        byte[] header = transporter.getHeader().toBytes();
        out.writeInt(header.length);                // 4 bytes
        out.writeBytes(header);                    // N bytes
        
        byte[] body = transporter.getBody();
        out.writeInt(body.length);                  // 4 bytes
        out.writeBytes(body);                      // M bytes
    }
}
```

**特点**:
- 继承 `MessageToByteEncoder<Transporter>`
- 使用 `@Sharable` 注解，可被多个 Channel 共享
- 将 Transporter 对象编码为字节流

### TransporterDecoder

**位置**: `org.apache.dolphinscheduler.extract.base.protocal.TransporterDecoder`

```java
public class TransporterDecoder extends ReplayingDecoder<TransporterDecoder.State> {
    enum State {
        MAGIC,           // 读取魔数
        VERSION,         // 读取版本
        HEADER_LENGTH,   // 读取 Header 长度
        HEADER,          // 读取 Header
        BODY_LENGTH,     // 读取 Body 长度
        BODY             // 读取 Body
    }
    
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state()) {
            case MAGIC:
                checkMagic(in.readByte());
                checkpoint(State.VERSION);
            case VERSION:
                checkVersion(in.readByte());
                checkpoint(State.HEADER_LENGTH);
            // ... 其他状态
        }
    }
}
```

**特点**:
- 继承 `ReplayingDecoder`，自动处理数据不足的情况
- 使用状态机模式，逐步解析协议
- 使用 `checkpoint()` 保存解析状态
- 验证 MAGIC 和 VERSION，确保协议正确

**优势**:
- `ReplayingDecoder` 会自动检查缓冲区是否有足够数据
- 数据不足时会暂停解析，等待更多数据到达
- 简化了边界检查代码

---

## 配置管理

### NettyServerConfig

**位置**: `org.apache.dolphinscheduler.extract.base.config.NettyServerConfig`

#### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `serverName` | String | - | 服务器名称 |
| `soBacklog` | int | 1024 | 连接队列大小 |
| `tcpNoDelay` | boolean | true | 禁用 Nagle 算法 |
| `soKeepalive` | boolean | true | 保持连接活跃 |
| `sendBufferSize` | int | 65535 | 发送缓冲区大小 |
| `receiveBufferSize` | int | 65535 | 接收缓冲区大小 |
| `workerThread` | int | CPU * 2 | Worker 线程数 |
| `connectionIdleTime` | long | 60000ms | 连接空闲超时时间 |
| `listenPort` | int | - | 监听端口 |

### NettyClientConfig

**位置**: `org.apache.dolphinscheduler.extract.base.config.NettyClientConfig`

#### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `workerThreads` | int | CPU * 2 | Worker 线程数 |
| `tcpNoDelay` | boolean | true | 禁用 Nagle 算法 |
| `soKeepalive` | boolean | true | 保持连接活跃 |
| `sendBufferSize` | int | 65535 | 发送缓冲区大小 |
| `receiveBufferSize` | int | 65535 | 接收缓冲区大小 |
| `connectTimeoutMillis` | int | 3000 | 连接超时时间 |
| `heartBeatIntervalMillis` | long | 10000ms | 心跳间隔 |
| `defaultRpcTimeoutMillis` | int | 10000 | 默认 RPC 超时时间 |

---

## 工具类

### NettyUtils

**位置**: `org.apache.dolphinscheduler.extract.base.utils.NettyUtils`

#### 功能

根据系统环境自动选择最优的 Channel 类型：

```java
public static Class<? extends ServerSocketChannel> getServerSocketChannelClass() {
    if (Epoll.isAvailable()) {
        return EpollServerSocketChannel.class;  // Linux 系统
    }
    return NioServerSocketChannel.class;         // 其他系统
}

public static Class<? extends SocketChannel> getSocketChannelClass() {
    if (Epoll.isAvailable()) {
        return EpollSocketChannel.class;         // Linux 系统
    }
    return NioSocketChannel.class;               // 其他系统
}
```

**Epoll vs NIO**:
- **Epoll**: Linux 系统专用，性能更好，使用 epoll 系统调用
- **NIO**: 跨平台，使用 Java NIO Selector

### ChannelUtils

**位置**: `org.apache.dolphinscheduler.extract.base.utils.ChannelUtils`

#### 功能

提供 Channel 地址转换工具：

```java
// 获取本地地址
public static String getLocalAddress(Channel channel)

// 获取远程地址
public static String getRemoteAddress(Channel channel)

// Channel 转换为 Host 对象
public static Host toAddress(Channel channel)
```

---

## 关键设计模式和技术点

### 1. 工厂模式

- **NettyRemotingServerFactory**: 创建 NettyRemotingServer
- **NettyRemotingClientFactory**: 创建 NettyRemotingClient

### 2. 单例模式

- `NettyUtils`、`ChannelUtils` 使用私有构造函数防止实例化

### 3. 策略模式

- 根据系统环境选择 Epoll 或 NIO
- 根据方法标识符选择不同的 MethodInvoker

### 4. 观察者模式

- 使用 `ResponseFuture` 和 `CountDownLatch` 实现请求-响应模式
- 客户端发送请求后等待响应

### 5. 线程模型

**服务器端**:
- Boss EventLoop: 1 个线程，处理连接接受
- Worker EventLoop: N 个线程，处理 I/O 事件
- MethodInvoker Executor: M 个线程，执行业务逻辑

**客户端**:
- Worker EventLoop: N 个线程，处理 I/O 事件
- 请求线程: 调用 `sendSync()` 的线程，等待响应

### 6. 连接管理

- **连接复用**: 客户端缓存 Channel，避免频繁创建连接
- **连接保活**: 
  - 服务器端：检测读空闲，超时关闭
  - 客户端：定期发送心跳
- **连接清理**: Channel 断开时自动清理缓存

### 7. 序列化

- 使用 JSON 序列化（`JsonSerializer`）
- Header 和 Body 分别序列化
- 支持类型信息传递（用于反序列化）

### 8. 异常处理

- **RemotingException**: 通用远程调用异常
- **RemotingTimeoutException**: 超时异常
- **RemotingTooMuchRequestException**: 请求过多异常
- **MethodInvocationException**: 方法调用异常

### 9. 性能优化

1. **零拷贝**: Netty 的 ByteBuf 支持零拷贝
2. **对象池**: 可考虑使用 Netty 的对象池减少 GC
3. **批量处理**: 支持批量发送请求（当前实现为单条）
4. **异步非阻塞**: 所有 I/O 操作都是异步非阻塞的

### 10. 监控和指标

- **RpcMetrics**: 记录 RPC 调用指标
- **ClientSyncDurationMetrics**: 客户端同步调用耗时
- **ClientSyncExceptionMetrics**: 客户端同步调用异常

---

## 总结

DolphinScheduler 的 `extract-base` 模块基于 Netty 实现了一个完整的 RPC 框架，具有以下特点：

1. **高性能**: 使用 Epoll/NIO，异步非阻塞 I/O
2. **可靠性**: 心跳保活、连接管理、异常处理
3. **易用性**: 配置简单，支持动态方法注册
4. **可扩展**: 支持自定义序列化、编解码器

### 学习要点

1. **Netty 基础**: ServerBootstrap、Bootstrap、ChannelPipeline、EventLoopGroup
2. **自定义协议**: 编解码器实现、协议设计
3. **同步调用**: CountDownLatch + Future 模式
4. **连接管理**: Channel 缓存、心跳机制
5. **线程模型**: EventLoop 线程与业务线程分离

### 扩展建议

1. 支持异步调用（Callback 模式）
2. 支持流式传输（Streaming）
3. 支持压缩（Gzip、Snappy）
4. 支持加密（TLS/SSL）
5. 支持负载均衡和故障转移

---

## 参考资源

- [Netty 官方文档](https://netty.io/)
- [Netty 实战](https://book.douban.com/subject/27038538/)
- [DolphinScheduler 官方文档](https://dolphinscheduler.apache.org/)

