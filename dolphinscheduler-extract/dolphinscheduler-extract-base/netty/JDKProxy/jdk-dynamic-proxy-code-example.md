# JDK 动态代理 RPC 客户端代码示例

## 一、简化版实现示例

为了帮助理解 JDK 动态代理在 RPC 中的使用，这里提供一个简化版的实现示例。

### 1.1 服务接口

```java
// 定义服务接口
public interface IWorkflowControlClient {
    @RpcMethod
    WorkflowManualTriggerResponse manualTriggerWorkflow(
        WorkflowManualTriggerRequest request);
}
```

### 1.2 客户端工厂（简化版）

```java
public class SimpleClients {
    private static final NettyRemotingClient nettyClient = new NettyRemotingClient();
    
    public static <T> T withService(Class<T> serviceInterface) {
        return new SimpleClientBuilder<>(serviceInterface);
    }
    
    static class SimpleClientBuilder<T> {
        private final Class<T> serviceInterface;
        
        SimpleClientBuilder(Class<T> serviceInterface) {
            this.serviceInterface = serviceInterface;
        }
        
        public T withHost(String serverHost) {
            // 使用 JDK 动态代理创建代理对象
            return (T) Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class[]{serviceInterface},
                new SimpleInvocationHandler(serverHost, nettyClient)
            );
        }
    }
}
```

### 1.3 调用处理器（简化版）

```java
class SimpleInvocationHandler implements InvocationHandler {
    private final String serverHost;
    private final NettyRemotingClient nettyClient;
    
    SimpleInvocationHandler(String serverHost, NettyRemotingClient nettyClient) {
        this.serverHost = serverHost;
        this.nettyClient = nettyClient;
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 检查是否有 @RpcMethod 注解
        if (method.getAnnotation(RpcMethod.class) == null) {
            // 非 RPC 方法，直接调用（如 Object 的方法）
            return method.invoke(proxy, args);
        }
        
        // 2. 生成方法标识符
        String methodIdentifier = method.getDeclaringClass().getName() 
            + "#" + method.getName();
        
        // 3. 序列化参数
        String requestBody = JsonSerializer.serialize(args[0]);
        
        // 4. 构建请求
        Transporter request = Transporter.of(
            TransporterHeader.of(methodIdentifier),
            requestBody
        );
        
        // 5. 发送 RPC 请求
        SyncRequestDto syncRequestDto = SyncRequestDto.builder()
            .transporter(request)
            .serverHost(Host.of(serverHost))
            .build();
        
        IRpcResponse response = nettyClient.sendSync(syncRequestDto);
        
        // 6. 处理响应
        if (!response.isSuccess()) {
            throw new RuntimeException("RPC 调用失败: " + response.getMessage());
        }
        
        // 7. 反序列化响应
        Class<?> returnType = method.getReturnType();
        return JsonSerializer.deserialize(response.getBody(), returnType);
    }
}
```

### 1.4 使用示例

```java
// 创建代理对象
IWorkflowControlClient client = SimpleClients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");

// 调用方法（实际会转换为 RPC 调用）
WorkflowManualTriggerResponse response = client.manualTriggerWorkflow(request);
```

## 二、完整实现对比

### 2.1 DolphinScheduler 的完整实现

DolphinScheduler 的实现比简化版更加完善，主要增加了：

1. **缓存机制**：避免重复创建代理对象和方法调用器
2. **方法调用器抽象**：支持不同的调用策略（同步、异步）
3. **异常处理**：完善的异常处理和重试机制
4. **注解支持**：支持超时、重试策略等配置

### 2.2 关键差异

| 特性 | 简化版 | DolphinScheduler 完整版 |
|------|--------|------------------------|
| **代理对象缓存** | ❌ 每次创建 | ✅ 使用 LoadingCache 缓存 |
| **方法调用器缓存** | ❌ 每次创建 | ✅ 使用 ConcurrentHashMap 缓存 |
| **调用策略** | ❌ 只支持同步 | ✅ 支持同步、异步（可扩展） |
| **重试机制** | ❌ 不支持 | ✅ 支持配置重试策略 |
| **超时控制** | ❌ 不支持 | ✅ 支持配置超时时间 |
| **异常处理** | ⚠️ 简单处理 | ✅ 完善的异常处理 |

## 三、JDK 动态代理生成的代码（伪代码）

### 3.1 代理类结构

```java
// JDK 动态生成的代理类（伪代码）
public final class $Proxy0 extends Proxy implements IWorkflowControlClient {
    private static final Method m0;  // hashCode()
    private static final Method m1;  // equals()
    private static final Method m2;  // toString()
    private static final Method m3;  // manualTriggerWorkflow()
    
    static {
        try {
            m0 = Class.forName("java.lang.Object").getMethod("hashCode");
            m1 = Class.forName("java.lang.Object").getMethod("equals", 
                Class.forName("java.lang.Object"));
            m2 = Class.forName("java.lang.Object").getMethod("toString");
            m3 = Class.forName("IWorkflowControlClient").getMethod(
                "manualTriggerWorkflow",
                Class.forName("WorkflowManualTriggerRequest"));
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        }
    }
    
    public $Proxy0(InvocationHandler h) {
        super(h);
    }
    
    @Override
    public final int hashCode() {
        try {
            return (Integer) h.invoke(this, m0, null);
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }
    
    @Override
    public final boolean equals(Object obj) {
        try {
            return (Boolean) h.invoke(this, m1, new Object[]{obj});
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }
    
    @Override
    public final String toString() {
        try {
            return (String) h.invoke(this, m2, null);
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }
    
    @Override
    public final WorkflowManualTriggerResponse manualTriggerWorkflow(
            WorkflowManualTriggerRequest request) {
        try {
            return (WorkflowManualTriggerResponse) h.invoke(
                this,
                m3,
                new Object[]{request}
            );
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }
}
```

### 3.2 关键点

1. **继承 Proxy**：所有 JDK 动态代理类都继承 `Proxy`
2. **实现接口**：实现指定的接口（`IWorkflowControlClient`）
3. **方法实现**：所有方法都调用 `InvocationHandler.invoke()`
4. **异常包装**：捕获异常并包装为 `UndeclaredThrowableException`

## 四、调试技巧

### 4.1 查看代理对象类型

```java
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");

// 查看代理对象的实际类型
System.out.println(client.getClass().getName());
// 输出：com.sun.proxy.$Proxy0

// 查看是否是代理对象
System.out.println(Proxy.isProxyClass(client.getClass()));
// 输出：true

// 获取 InvocationHandler
InvocationHandler handler = Proxy.getInvocationHandler(client);
System.out.println(handler.getClass().getName());
// 输出：ClientInvocationHandler
```

### 4.2 查看代理类字节码

```java
// 保存代理类字节码到文件
System.setProperty("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");

// 创建代理对象
IWorkflowControlClient client = Clients
    .withService(IWorkflowControlClient.class)
    .withHost("192.168.1.100:5678");

// 代理类字节码会保存到项目根目录的 com/sun/proxy/ 目录下
```

### 4.3 添加日志调试

```java
class DebugInvocationHandler implements InvocationHandler {
    private final InvocationHandler delegate;
    
    DebugInvocationHandler(InvocationHandler delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        System.out.println("调用方法: " + method.getName());
        System.out.println("参数: " + Arrays.toString(args));
        
        long start = System.currentTimeMillis();
        try {
            Object result = delegate.invoke(proxy, method, args);
            long duration = System.currentTimeMillis() - start;
            System.out.println("方法调用成功，耗时: " + duration + "ms");
            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - start;
            System.out.println("方法调用失败，耗时: " + duration + "ms，异常: " + e);
            throw e;
        }
    }
}
```

## 五、常见问题

### 5.1 为什么接口方法必须有 @RpcMethod 注解？

**原因**：
- 不是所有接口方法都需要转换为 RPC 调用
- 例如 `Object` 的方法（`toString()`、`equals()` 等）应该直接调用
- `@RpcMethod` 注解用于标识哪些方法需要转换为 RPC 调用

### 5.2 为什么使用缓存？

**原因**：
1. **性能优化**：创建代理对象和方法调用器有开销
2. **内存优化**：避免重复创建相同的对象
3. **线程安全**：使用 `ConcurrentHashMap` 和 `LoadingCache` 保证线程安全

### 5.3 代理对象可以序列化吗？

**不可以**。JDK 动态代理生成的代理类没有实现 `Serializable` 接口，不能直接序列化。

如果需要序列化，可以考虑：
1. 序列化接口定义和参数，在接收端重新创建代理对象
2. 使用其他序列化框架（如 Kryo）支持代理对象序列化

### 5.4 如何支持异步调用？

可以通过扩展 `ClientMethodInvoker` 接口支持异步调用：

```java
// 异步方法调用器
class AsyncClientMethodInvoker extends AbstractClientMethodInvoker {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 发送异步请求
        CompletableFuture<IRpcResponse> future = nettyRemotingClient.sendAsync(...);
        
        // 返回 CompletableFuture
        return future.thenApply(response -> {
            return JsonSerializer.deserialize(response.getBody(), method.getReturnType());
        });
    }
}
```

## 六、总结

JDK 动态代理在 RPC 客户端中的使用原理：

1. **代理对象创建**：使用 `Proxy.newProxyInstance()` 动态生成代理类
2. **方法拦截**：所有方法调用都被 `InvocationHandler.invoke()` 拦截
3. **注解检查**：只有标记了 `@RpcMethod` 的方法才转换为 RPC 调用
4. **RPC 转换**：将方法调用转换为 RPC 请求，通过 Netty 发送
5. **响应处理**：接收响应，反序列化并返回结果

**优势**：
- 客户端代码像调用本地方法一样调用远程服务
- 类型安全，编译时检查
- 代码简洁，自动处理序列化、网络通信
