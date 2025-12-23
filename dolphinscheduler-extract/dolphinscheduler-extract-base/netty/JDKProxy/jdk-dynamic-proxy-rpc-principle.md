# JDK 动态代理在 RPC 客户端中的使用原理

## 一、概述

在 DolphinScheduler 中，客户端调用远程服务使用了 **JDK 动态代理**技术。通过动态代理，可以将本地方法调用透明地转换为远程 RPC 调用，使得客户端代码可以像调用本地方法一样调用远程服务。

## 二、从实际代码出发

### 2.1 客户端调用代码

```java
// TriggerWorkflowExecutorDelegate.java
final WorkflowManualTriggerResponse workflowManualTriggerResponse = Clients
    .withService(IWorkflowControlClient.class)           // 1. 指定服务接口
    .withHost(masterServer.getHost() + ":" + masterServer.getPort())  // 2. 指定服务端地址
    .manualTriggerWorkflow(transform2WorkflowTriggerRequest(triggerWorkflowDTO));  // 3. 调用方法
```

**关键点**：
- `IWorkflowControlClient` 是一个**接口**，没有实现类
- `Clients.withService()` 返回的实际上是一个**代理对象**
- 调用 `manualTriggerWorkflow()` 时，实际上是通过代理拦截，转换为 RPC 调用

### 2.2 服务接口定义

```java
// IWorkflowControlClient.java
@RpcService
public interface IWorkflowControlClient {
    
    @RpcMethod  // 标记为 RPC 方法
    WorkflowManualTriggerResponse manualTriggerWorkflow(
        final WorkflowManualTriggerRequest workflowManualTriggerRequest);
}
```

**关键点**：
- 接口方法使用 `@RpcMethod` 注解标记
- 只有标记了 `@RpcMethod` 的方法才会被代理拦截，转换为 RPC 调用

## 三、JDK 动态代理核心原理

### 3.1 什么是 JDK 动态代理？

JDK 动态代理是 Java 提供的一种代理机制，可以在运行时动态创建代理对象，拦截方法调用。

**核心类**：
- `java.lang.reflect.Proxy`：用于创建代理对象
- `java.lang.reflect.InvocationHandler`：方法调用处理器

### 3.2 JDK 动态代理的基本使用

```java
// 1. 定义接口
interface IService {
    String sayHello(String name);
}

// 2. 实现 InvocationHandler
class MyInvocationHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 拦截方法调用，执行自定义逻辑
        System.out.println("调用方法: " + method.getName());
        // 可以在这里执行远程调用、日志记录等
        return "Hello " + args[0];
    }
}

// 3. 创建代理对象
IService proxy = (IService) Proxy.newProxyInstance(
    IService.class.getClassLoader(),  // 类加载器
    new Class[]{IService.class},      // 接口数组
    new MyInvocationHandler()         // 调用处理器
);

// 4. 使用代理对象
String result = proxy.sayHello("World");  // 实际会调用 MyInvocationHandler.invoke()
```

**工作原理**：
1. `Proxy.newProxyInstance()` 在运行时动态生成一个实现了指定接口的代理类
2. 代理类实现了接口的所有方法
3. 每个方法内部都调用 `InvocationHandler.invoke()`
4. 客户端调用代理对象的方法时，实际执行的是 `invoke()` 方法

### 3.3 动态生成的代理类（简化版）

JDK 动态代理在运行时生成的代理类大致如下：

```java
// 动态生成的代理类（简化版）
public class $Proxy0 implements IWorkflowControlClient {
    private InvocationHandler h;  // 调用处理器
    
    public $Proxy0(InvocationHandler h) {
        this.h = h;
    }
    
    @Override
    public WorkflowManualTriggerResponse manualTriggerWorkflow(
            WorkflowManualTriggerRequest request) {
        // 调用 InvocationHandler.invoke()
        return (WorkflowManualTriggerResponse) h.invoke(
            this,
            IWorkflowControlClient.class.getMethod("manualTriggerWorkflow", ...),
            new Object[]{request}
        );
    }
}
```

## 四、DolphinScheduler 中的实现

### 4.1 整体架构

```
客户端代码
    ↓
Clients.withService(IWorkflowControlClient.class)
    ↓
JdkDynamicRpcClientProxyFactory.getProxyClient()
    ↓
Proxy.newProxyInstance() 创建代理对象
    ↓
ClientInvocationHandler (InvocationHandler)
    ↓
SyncClientMethodInvoker.invoke()
    ↓
NettyRemotingClient.sendSync() 发送 RPC 请求
    ↓
服务端处理并返回响应
```

### 4.2 详细流程分析

#### 步骤1：创建代理对象

```java
// Clients.java
public static <T> JdkDynamicRpcClientProxyBuilder<T> withService(Class<T> serviceClazz) {
    return new JdkDynamicRpcClientProxyBuilder<>(serviceClazz);
}

public T withHost(String serviceHost) {
    return jdkDynamicRpcClientProxyFactory.getProxyClient(serviceHost, serviceClazz);
}
```

**作用**：
- `withService()` 指定要代理的接口类型
- `withHost()` 指定服务端地址，并创建代理对象

#### 步骤2：代理对象创建（使用缓存）

```java
// JdkDynamicRpcClientProxyFactory.java
public <T> T getProxyClient(String serverHost, Class<T> clientInterface) {
    return (T) proxyClientCache.get(serverHost)
        .computeIfAbsent(
            clientInterface.getName(), 
            key -> newProxyClient(serverHost, clientInterface)
        );
}

private <T> T newProxyClient(String serverHost, Class<T> clientInterface) {
    return (T) Proxy.newProxyInstance(
        clientInterface.getClassLoader(),           // 使用接口的类加载器
        new Class[]{clientInterface},               // 要实现的接口
        new ClientInvocationHandler(                // 调用处理器
            Host.of(serverHost), 
            nettyRemotingClient
        )
    );
}
```

**关键点**：
1. **缓存机制**：使用 `LoadingCache` 缓存代理对象，避免重复创建
2. **Proxy.newProxyInstance()**：JDK 提供的创建代理对象的方法
3. **ClientInvocationHandler**：自定义的调用处理器，负责拦截方法调用

#### 步骤3：方法调用拦截

```java
// ClientInvocationHandler.java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // 1. 检查方法是否有 @RpcMethod 注解
    if (method.getAnnotation(RpcMethod.class) == null) {
        // 如果没有注解，直接调用原方法（如 Object 的方法）
        return method.invoke(proxy, args);
    }
    
    // 2. 获取或创建方法调用器
    ClientMethodInvoker methodInvoker = methodInvokerMap.computeIfAbsent(
        method.toGenericString(), 
        m -> new SyncClientMethodInvoker(serverHost, method, nettyRemotingClient)
    );
    
    // 3. 执行方法调用（转换为 RPC 调用）
    return methodInvoker.invoke(proxy, method, args);
}
```

**关键点**：
1. **方法拦截**：所有接口方法调用都会被 `invoke()` 拦截
2. **注解检查**：只有标记了 `@RpcMethod` 的方法才转换为 RPC 调用
3. **方法调用器**：使用 `SyncClientMethodInvoker` 处理实际的 RPC 调用

#### 步骤4：转换为 RPC 请求

```java
// SyncClientMethodInvoker.java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // 1. 获取 @RpcMethod 注解信息
    RpcMethod sync = method.getAnnotation(RpcMethod.class);
    
    // 2. 构建请求传输对象
    final Transporter transporter = Transporter.of(
        TransporterHeader.of(methodIdentifier),  // 方法标识符
        JsonSerializer.serialize(StandardRpcRequest.of(args))  // 序列化参数
    );
    
    // 3. 构建同步请求 DTO
    final SyncRequestDto syncRequestDto = SyncRequestDto.builder()
        .timeoutMillis(sync.timeout())
        .retryStrategy(sync.retry())
        .transporter(transporter)
        .serverHost(serverHost)
        .build();
    
    // 4. 发送同步 RPC 请求
    IRpcResponse iRpcResponse = nettyRemotingClient.sendSync(syncRequestDto);
    
    // 5. 处理响应
    if (!iRpcResponse.isSuccess()) {
        throw MethodInvocationException.of(iRpcResponse.getMessage());
    }
    
    // 6. 反序列化响应并返回
    if (iRpcResponse.getBody() == null) {
        return null;
    }
    Class<?> responseClass = method.getReturnType();
    return JsonSerializer.deserialize(iRpcResponse.getBody(), responseClass);
}
```

**关键点**：
1. **方法标识符**：从方法名生成唯一标识符（如 `IWorkflowControlClient#manualTriggerWorkflow`）
2. **参数序列化**：将方法参数序列化为 JSON
3. **发送请求**：通过 `NettyRemotingClient` 发送同步 RPC 请求
4. **响应处理**：反序列化响应并返回

## 五、完整调用流程

### 5.1 时序图

```
客户端代码
    ↓
Clients.withService(IWorkflowControlClient.class)
    ↓
JdkDynamicRpcClientProxyFactory.getProxyClient()
    ↓
Proxy.newProxyInstance() 创建代理对象
    ↓
返回代理对象（实现了 IWorkflowControlClient 接口）
    ↓
客户端调用：proxy.manualTriggerWorkflow(request)
    ↓
JDK 动态代理拦截：ClientInvocationHandler.invoke()
    ↓
检查 @RpcMethod 注解
    ↓
创建 SyncClientMethodInvoker
    ↓
SyncClientMethodInvoker.invoke()
    ↓
构建 Transporter（包含方法标识符和参数）
    ↓
NettyRemotingClient.sendSync()
    ↓
通过 Netty 发送 RPC 请求
    ↓
服务端处理请求
    ↓
返回响应
    ↓
NettyRemotingClient 接收响应
    ↓
反序列化响应
    ↓
返回结果给客户端代码
```

### 5.2 代码执行示例

```java
// 1. 客户端代码
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");

// 2. 实际返回的是代理对象
// client 的实际类型是：$Proxy0（JDK 动态生成的代理类）

// 3. 调用方法
WorkflowManualTriggerResponse response = client.manualTriggerWorkflow(request);

// 4. 实际执行流程：
//    - JDK 代理拦截方法调用
//    - 调用 ClientInvocationHandler.invoke()
//    - 检查 @RpcMethod 注解（有）
//    - 创建 SyncClientMethodInvoker
//    - 构建 RPC 请求
//    - 通过 Netty 发送请求
//    - 等待响应
//    - 反序列化响应
//    - 返回结果
```

## 六、关键组件详解

### 6.1 Clients（客户端工厂）

```java
public class Clients {
    private static final JdkDynamicRpcClientProxyFactory jdkDynamicRpcClientProxyFactory =
        new JdkDynamicRpcClientProxyFactory(
            NettyRemotingClientFactory.buildNettyRemotingClient(new NettyClientConfig())
        );
    
    public static <T> JdkDynamicRpcClientProxyBuilder<T> withService(Class<T> serviceClazz) {
        return new JdkDynamicRpcClientProxyBuilder<>(serviceClazz);
    }
}
```

**作用**：
- 提供静态工厂方法，简化代理对象的创建
- 内部维护一个 `JdkDynamicRpcClientProxyFactory` 实例

### 6.2 JdkDynamicRpcClientProxyFactory（代理工厂）

```java
class JdkDynamicRpcClientProxyFactory {
    private final NettyRemotingClient nettyRemotingClient;
    
    // 使用缓存避免重复创建代理对象
    private static final LoadingCache<String, Map<String, Object>> proxyClientCache = ...;
    
    public <T> T getProxyClient(String serverHost, Class<T> clientInterface) {
        return (T) proxyClientCache.get(serverHost)
            .computeIfAbsent(
                clientInterface.getName(), 
                key -> newProxyClient(serverHost, clientInterface)
            );
    }
    
    private <T> T newProxyClient(String serverHost, Class<T> clientInterface) {
        return (T) Proxy.newProxyInstance(
            clientInterface.getClassLoader(),
            new Class[]{clientInterface},
            new ClientInvocationHandler(Host.of(serverHost), nettyRemotingClient)
        );
    }
}
```

**关键特性**：
1. **缓存机制**：使用 `LoadingCache` 缓存代理对象，按 `serverHost` 和 `clientInterface` 缓存
2. **代理创建**：使用 `Proxy.newProxyInstance()` 创建代理对象
3. **调用处理器**：使用 `ClientInvocationHandler` 处理方法调用

### 6.3 ClientInvocationHandler（调用处理器）

```java
class ClientInvocationHandler implements InvocationHandler {
    private final NettyRemotingClient nettyRemotingClient;
    private final Map<String, ClientMethodInvoker> methodInvokerMap;
    private final Host serverHost;
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 检查是否有 @RpcMethod 注解
        if (method.getAnnotation(RpcMethod.class) == null) {
            return method.invoke(proxy, args);  // 非 RPC 方法，直接调用
        }
        
        // 获取或创建方法调用器
        ClientMethodInvoker methodInvoker = methodInvokerMap.computeIfAbsent(
            method.toGenericString(),
            m -> new SyncClientMethodInvoker(serverHost, method, nettyRemotingClient)
        );
        
        // 执行方法调用
        return methodInvoker.invoke(proxy, method, args);
    }
}
```

**关键特性**：
1. **实现 InvocationHandler**：所有方法调用都会被 `invoke()` 拦截
2. **注解检查**：只有标记了 `@RpcMethod` 的方法才转换为 RPC 调用
3. **方法调用器缓存**：使用 `ConcurrentHashMap` 缓存方法调用器，避免重复创建

### 6.4 SyncClientMethodInvoker（方法调用器）

```java
class SyncClientMethodInvoker extends AbstractClientMethodInvoker {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 获取 @RpcMethod 注解
        RpcMethod sync = method.getAnnotation(RpcMethod.class);
        
        // 2. 构建请求
        final Transporter transporter = Transporter.of(
            TransporterHeader.of(methodIdentifier),
            JsonSerializer.serialize(StandardRpcRequest.of(args))
        );
        
        // 3. 发送同步 RPC 请求
        IRpcResponse iRpcResponse = nettyRemotingClient.sendSync(syncRequestDto);
        
        // 4. 处理响应
        return JsonSerializer.deserialize(iRpcResponse.getBody(), responseClass);
    }
}
```

**关键特性**：
1. **方法标识符**：从方法生成唯一标识符（如 `IWorkflowControlClient#manualTriggerWorkflow`）
2. **参数序列化**：将方法参数序列化为 JSON
3. **同步调用**：使用 `sendSync()` 发送同步 RPC 请求
4. **响应反序列化**：将响应反序列化为方法返回类型

## 七、为什么使用 JDK 动态代理？

### 7.1 优势

1. **透明调用**：客户端代码可以像调用本地方法一样调用远程服务
2. **类型安全**：编译时检查接口方法签名，避免运行时错误
3. **代码简洁**：不需要手动编写 RPC 调用代码
4. **易于维护**：接口变更时，只需修改接口定义

### 7.2 对比其他方案

#### 方案1：手动 RPC 调用（不使用代理）

```java
// ❌ 不使用代理的方式
NettyRemotingClient client = new NettyRemotingClient();
Transporter request = buildRequest("manualTriggerWorkflow", args);
IRpcResponse response = client.sendSync(request);
WorkflowManualTriggerResponse result = deserialize(response);
```

**问题**：
- 代码冗长，需要手动构建请求
- 容易出错，方法名、参数类型需要手动匹配
- 类型不安全，编译时无法检查

#### 方案2：使用 JDK 动态代理（当前方案）

```java
// ✅ 使用代理的方式
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");
WorkflowManualTriggerResponse result = client.manualTriggerWorkflow(request);
```

**优势**：
- 代码简洁，像调用本地方法
- 类型安全，编译时检查
- 自动处理序列化/反序列化

## 八、代理对象的生命周期

### 8.1 创建时机

```java
// 第一次调用时创建
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");
// ↑ 此时创建代理对象
```

### 8.2 缓存机制

```java
// JdkDynamicRpcClientProxyFactory
private static final LoadingCache<String, Map<String, Object>> proxyClientCache = 
    CacheBuilder.newBuilder()
        .expireAfterAccess(Duration.ofHours(1))  // 1小时未使用则过期
        .build(...);
```

**缓存策略**：
- **Key**：`serverHost`（服务端地址）
- **Value**：`Map<接口名, 代理对象>`
- **过期时间**：1小时未访问则自动清理

### 8.3 方法调用器缓存

```java
// ClientInvocationHandler
private final Map<String, ClientMethodInvoker> methodInvokerMap = new ConcurrentHashMap<>();
```

**缓存策略**：
- **Key**：`method.toGenericString()`（方法的完整签名）
- **Value**：`ClientMethodInvoker` 实例
- **生命周期**：与 `ClientInvocationHandler` 实例相同

## 九、方法标识符生成

### 9.1 方法标识符的作用

方法标识符用于在服务端唯一标识要调用的方法。

### 9.2 生成规则

```java
// AbstractClientMethodInvoker
protected final String methodIdentifier;

// 生成方式（简化版）
methodIdentifier = interfaceName + "#" + methodName + "(" + parameterTypes + ")";

// 示例
// IWorkflowControlClient#manualTriggerWorkflow(WorkflowManualTriggerRequest)
```

### 9.3 服务端匹配

服务端根据方法标识符查找对应的 `ServerMethodInvoker`：

```java
// JdkDynamicServerHandler
ServerMethodInvoker methodInvoker = methodInvokerMap.get(methodIdentifier);
if (methodInvoker == null) {
    // 方法未找到，返回错误
}
Object result = methodInvoker.invoke(args);
```

## 十、服务端处理逻辑

### 10.1 服务端方法注册

在服务端启动时，需要将实现了接口的 Bean 注册到 `JdkDynamicServerHandler`：

```java
// RpcServer.java
@Override
public void registerServerMethodInvokerProvider(Object serverMethodInvokerProviderBean) {
    // 1. 遍历 Bean 实现的所有接口
    for (Class<?> anInterface : serverMethodInvokerProviderBean.getClass().getInterfaces()) {
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
            
            // 5. 创建 ServerMethodInvoker
            ServerMethodInvoker serverMethodInvoker =
                new ServerMethodInvokerImpl(serverMethodInvokerProviderBean, method);
            
            // 6. 注册到 JdkDynamicServerHandler
            nettyRemotingServer.registerMethodInvoker(serverMethodInvoker);
        }
    }
}
```

**注册示例**：
```java
// 服务端实现类
@Component
public class WorkflowControlClient implements IWorkflowControlClient {
    @Override
    public WorkflowManualTriggerResponse manualTriggerWorkflow(
            WorkflowManualTriggerRequest request) {
        // 实际的业务逻辑
        return WorkflowManualTriggerResponse.success(workflowInstanceId);
    }
}

// 注册到 RPC 服务器
rpcServer.registerServerMethodInvokerProvider(workflowControlClient);
// ↑ 会扫描 IWorkflowControlClient 接口的所有 @RpcMethod 方法
// ↑ 为每个方法创建 ServerMethodInvoker 并注册
```

### 10.2 服务端方法调用器

```java
// ServerMethodInvokerImpl.java
class ServerMethodInvokerImpl implements ServerMethodInvoker {
    private final Object serviceBean;      // 服务实现 Bean
    private final Method method;           // 接口方法
    private final String methodIdentify;   // 方法标识符
    
    ServerMethodInvokerImpl(Object serviceBean, Method method) {
        this.serviceBean = serviceBean;
        this.method = method;
        // 生成方法标识符（与客户端一致）
        this.methodIdentify = method.toGenericString();
    }
    
    @Override
    public Object invoke(Object... args) throws Throwable {
        // 使用反射调用实际的方法
        return method.invoke(serviceBean, args);
    }
}
```

**关键点**：
1. **方法标识符**：使用 `method.toGenericString()` 生成，与客户端保持一致
2. **反射调用**：使用 `Method.invoke()` 调用实际的服务实现方法
3. **异常处理**：捕获 `InvocationTargetException`，解包获取真正的异常

### 10.3 服务端请求处理流程

```java
// JdkDynamicServerHandler.processReceived()
private void processReceived(final Channel channel, final Transporter transporter) {
    // 1. 提取方法标识符
    final String methodIdentifier = transporter.getHeader().getMethodIdentifier();
    
    // 2. 检查是否是心跳请求
    if (HeartBeatTransporter.METHOD_IDENTIFY.equals(methodIdentifier)) {
        return;  // 心跳请求，直接返回
    }
    
    // 3. 根据方法标识符查找 ServerMethodInvoker
    ServerMethodInvoker methodInvoker = methodInvokerMap.get(methodIdentifier);
    
    // 4. 检查方法是否存在
    if (methodInvoker == null) {
        // 方法未找到，返回错误响应
        StandardRpcResponse iRpcResponse = 
            StandardRpcResponse.fail("Cannot find the ServerMethodInvoker of " + methodIdentifier);
        Transporter response = Transporter.of(
            TransporterHeader.of(transporter.getHeader().getOpaque(), methodIdentifier),
            iRpcResponse
        );
        channel.writeAndFlush(response);
        return;
    }
    
    // 5. 在线程池中异步执行方法调用
    methodInvokeExecutor.execute(() -> {
        StandardRpcResponse iRpcResponse;
        try {
            // 5.1 反序列化请求参数
            StandardRpcRequest standardRpcRequest = 
                JsonSerializer.deserialize(transporter.getBody(), StandardRpcRequest.class);
            
            // 5.2 反序列化方法参数
            Object[] args = null;
            if (standardRpcRequest.getArgs() != null && standardRpcRequest.getArgs().length > 0) {
                args = new Object[standardRpcRequest.getArgs().length];
                for (int i = 0; i < standardRpcRequest.getArgs().length; i++) {
                    args[i] = JsonSerializer.deserialize(
                        standardRpcRequest.getArgs()[i],
                        standardRpcRequest.getArgsTypes()[i]
                    );
                }
            }
            
            // 5.3 调用实际的方法
            Object result = methodInvoker.invoke(args);
            
            // 5.4 构建成功响应
            if (result == null) {
                iRpcResponse = StandardRpcResponse.success(null, null);
            } else {
                iRpcResponse = StandardRpcResponse.success(
                    JsonSerializer.serialize(result), 
                    result.getClass()
                );
            }
        } catch (Throwable e) {
            // 5.5 处理异常，构建失败响应
            log.error("Invoke method {} failed, {}.", methodIdentifier, e.getMessage(), e);
            iRpcResponse = StandardRpcResponse.fail(e.getMessage());
        }
        
        // 5.6 构建响应并发送
        TransporterHeader responseHeader = 
            TransporterHeader.of(transporter.getHeader().getOpaque(), methodIdentifier);
        Transporter response = Transporter.of(responseHeader, iRpcResponse);
        channel.writeAndFlush(response);
    });
}
```

**关键步骤**：
1. **提取方法标识符**：从请求的 `TransporterHeader` 中获取
2. **查找方法调用器**：根据方法标识符从 `methodInvokerMap` 中查找
3. **异步执行**：在线程池中执行方法调用，避免阻塞 Netty EventLoop 线程
4. **参数反序列化**：将请求体中的 JSON 反序列化为方法参数类型
5. **方法调用**：使用反射调用实际的服务实现方法
6. **结果序列化**：将方法返回值序列化为 JSON
7. **发送响应**：通过 Channel 发送响应，使用请求的 `opaque` 匹配

### 10.4 方法标识符匹配机制

**客户端生成**：
```java
// SyncClientMethodInvoker
methodIdentifier = method.toGenericString();
// 示例：IWorkflowControlClient#manualTriggerWorkflow(WorkflowManualTriggerRequest)
```

**服务端注册**：
```java
// ServerMethodInvokerImpl
methodIdentify = method.toGenericString();
// 示例：IWorkflowControlClient#manualTriggerWorkflow(WorkflowManualTriggerRequest)
methodInvokerMap.put(methodIdentify, serverMethodInvoker);
```

**服务端匹配**：
```java
// JdkDynamicServerHandler
String methodIdentifier = transporter.getHeader().getMethodIdentifier();
ServerMethodInvoker methodInvoker = methodInvokerMap.get(methodIdentifier);
// ↑ 使用相同的方法标识符匹配
```

**关键点**：
- 客户端和服务端使用**相同的方法标识符生成规则**（`method.toGenericString()`）
- 确保客户端发送的方法标识符与服务端注册的完全一致
- 方法标识符包含：接口名、方法名、参数类型

### 10.5 完整的客户端-服务端交互

```
客户端（代理对象）
    ↓
1. 调用 proxy.manualTriggerWorkflow(request)
    ↓
2. ClientInvocationHandler.invoke() 拦截
    ↓
3. 生成方法标识符：IWorkflowControlClient#manualTriggerWorkflow(...)
    ↓
4. 序列化参数为 JSON
    ↓
5. 构建 Transporter（包含 methodIdentifier 和参数）
    ↓
6. 通过 Netty 发送请求
    ↓
┌─────────────────────────────────────────────────────────┐
│                   网络传输                                │
└─────────────────────────────────────────────────────────┘
    ↓
服务端（JdkDynamicServerHandler）
    ↓
7. 接收请求，提取 methodIdentifier
    ↓
8. 从 methodInvokerMap 查找 ServerMethodInvoker
    ↓
9. 反序列化参数
    ↓
10. 调用 methodInvoker.invoke(args)
    ↓
11. 反射调用实际的服务实现方法
    ↓
12. 序列化返回值
    ↓
13. 构建响应（包含相同的 opaque）
    ↓
14. 通过 Channel 发送响应
    ↓
┌─────────────────────────────────────────────────────────┐
│                   网络传输                                │
└─────────────────────────────────────────────────────────┘
    ↓
客户端（代理对象）
    ↓
15. 接收响应
    ↓
16. 反序列化响应
    ↓
17. 返回结果给客户端代码
```

### 10.6 服务端线程模型

```java
// JdkDynamicServerHandler
methodInvokeExecutor.execute(() -> {
    // 方法调用在线程池中执行
    Object result = methodInvoker.invoke(args);
});
```

**线程模型**：
- **Netty EventLoop 线程**：接收请求、发送响应（非阻塞）
- **方法调用线程池**：执行实际的方法调用（可能阻塞）

**优势**：
- EventLoop 线程不被阻塞，可以处理更多请求
- 方法调用在线程池中执行，支持并发处理多个请求
- 线程池大小可配置，控制并发度

### 10.7 错误处理

#### 10.7.1 方法未找到

```java
if (methodInvoker == null) {
    log.error("Cannot find the ServerMethodInvoker of method: {}, from client: {}, registered methods: {}",
        methodIdentifier, ChannelUtils.getRemoteAddress(channel), methodInvokerMap.keySet());
    StandardRpcResponse iRpcResponse = 
        StandardRpcResponse.fail("Cannot find the ServerMethodInvoker of " + methodIdentifier);
    // 返回错误响应
    channel.writeAndFlush(response);
    return;
}
```

**可能原因**：
- 服务端未注册该方法
- 方法标识符不匹配
- 服务端启动顺序问题

#### 10.7.2 方法调用异常

```java
try {
    Object result = methodInvoker.invoke(args);
    // 构建成功响应
} catch (Throwable e) {
    log.error("Invoke method {} failed, {}.", methodIdentifier, e.getMessage(), e);
    iRpcResponse = StandardRpcResponse.fail(e.getMessage());
    // 构建失败响应
}
```

**异常处理**：
- 捕获所有异常，记录日志
- 将异常信息封装到响应中
- 客户端收到失败响应后抛出异常

#### 10.7.3 线程池满

```java
} catch (RejectedExecutionException e) {
    log.warn("NettyRemotingServer's thread pool is full, discard msg {} from {}", 
        transporter, ChannelUtils.getRemoteAddress(channel));
    StandardRpcResponse iRpcResponse = 
        StandardRpcResponse.fail("NettyRemotingServer's thread pool is full");
    // 返回错误响应
    channel.writeAndFlush(response);
}
```

**处理方式**：
- 线程池满时，拒绝执行新任务
- 返回错误响应，告知客户端服务繁忙
- 客户端可以重试或降级处理

## 十一、异常处理

### 11.1 异常传播

```java
// ClientInvocationHandler.invoke()
try {
    return methodInvoker.invoke(proxy, method, args);
} catch (UndeclaredThrowableException undeclaredThrowableException) {
    throw undeclaredThrowableException.getCause();  // 解包异常
} catch (Throwable throwable) {
    throw throwable;
}
```

**关键点**：
- `UndeclaredThrowableException` 是 JDK 动态代理抛出的包装异常
- 需要解包获取真正的异常

### 11.2 RPC 异常转换

```java
// SyncClientMethodInvoker.invoke()
IRpcResponse iRpcResponse = nettyRemotingClient.sendSync(syncRequestDto);
if (!iRpcResponse.isSuccess()) {
    throw MethodInvocationException.of(iRpcResponse.getMessage());
}
```

**异常类型**：
- `RemoteException`：网络异常、连接异常
- `MethodInvocationException`：服务端方法调用异常
- `RemoteTimeoutException`：请求超时异常

## 十二、总结

### 12.1 核心原理

1. **JDK 动态代理**：在运行时动态创建实现了接口的代理类
2. **方法拦截**：所有方法调用都被 `InvocationHandler.invoke()` 拦截
3. **RPC 转换**：将方法调用转换为 RPC 请求，通过 Netty 发送
4. **响应处理**：接收响应，反序列化并返回结果

### 12.2 关键组件

| 组件 | 作用 |
|------|------|
| `Clients` | 提供静态工厂方法，简化代理对象创建 |
| `JdkDynamicRpcClientProxyFactory` | 代理对象工厂，使用缓存机制 |
| `ClientInvocationHandler` | 方法调用拦截器，检查注解并分发 |
| `SyncClientMethodInvoker` | 方法调用器，转换为 RPC 请求 |
| `NettyRemotingClient` | RPC 客户端，发送请求并接收响应 |

### 12.3 优势

1. **透明调用**：客户端代码像调用本地方法一样调用远程服务
2. **类型安全**：编译时检查接口方法签名
3. **代码简洁**：自动处理序列化、反序列化、网络通信
4. **易于维护**：接口变更时只需修改接口定义

### 12.4 适用场景

- **RPC 框架**：将本地方法调用转换为远程调用
- **AOP 编程**：在方法调用前后添加横切逻辑（日志、事务等）
- **接口适配**：适配不同的实现，统一接口

## 十三、代理对象的使用总结

### 13.1 客户端代理对象的使用

**创建代理对象**：
```java
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");
```

**调用方法**：
```java
WorkflowManualTriggerResponse response = client.manualTriggerWorkflow(request);
```

**实际执行**：
1. 代理对象拦截方法调用
2. 转换为 RPC 请求
3. 通过 Netty 发送到服务端
4. 等待响应并返回

### 13.2 服务端方法注册

**服务实现**：
```java
@Component
public class WorkflowControlClient implements IWorkflowControlClient {
    @Override
    public WorkflowManualTriggerResponse manualTriggerWorkflow(
            WorkflowManualTriggerRequest request) {
        // 业务逻辑
        return WorkflowManualTriggerResponse.success(workflowInstanceId);
    }
}
```

**注册到 RPC 服务器**：
```java
rpcServer.registerServerMethodInvokerProvider(workflowControlClient);
// ↑ 扫描接口方法，创建 ServerMethodInvoker，注册到 methodInvokerMap
```

### 13.3 方法标识符的匹配

**客户端生成**：
```java
methodIdentifier = method.toGenericString();
// IWorkflowControlClient#manualTriggerWorkflow(WorkflowManualTriggerRequest)
```

**服务端注册**：
```java
methodIdentify = method.toGenericString();
// IWorkflowControlClient#manualTriggerWorkflow(WorkflowManualTriggerRequest)
methodInvokerMap.put(methodIdentify, serverMethodInvoker);
```

**服务端匹配**：
```java
String methodIdentifier = transporter.getHeader().getMethodIdentifier();
ServerMethodInvoker methodInvoker = methodInvokerMap.get(methodIdentifier);
// ↑ 使用相同的方法标识符匹配
```

### 13.4 完整的调用链路

```
客户端代码
  → 代理对象（JDK 动态生成）
  → ClientInvocationHandler.invoke()
  → SyncClientMethodInvoker.invoke()
  → NettyRemotingClient.sendSync()
  → 网络传输
  → JdkDynamicServerHandler.processReceived()
  → ServerMethodInvoker.invoke()
  → 服务实现方法（反射调用）
  → 返回结果
  → 网络传输
  → NettyRemotingClient 接收响应
  → 反序列化
  → 返回给客户端代码
```

## 十四、相关文件

- **客户端工厂**：`Clients.java`
- **代理工厂**：`JdkDynamicRpcClientProxyFactory.java`
- **调用处理器**：`ClientInvocationHandler.java`
- **方法调用器**：`SyncClientMethodInvoker.java`
- **RPC 客户端**：`NettyRemotingClient.java`
- **服务接口示例**：`IWorkflowControlClient.java`
- **RPC 方法注解**：`RpcMethod.java`
