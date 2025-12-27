# ZookeeperRegistry 启动时连接 ZooKeeper 详解

## 目录

- [一、ZookeeperRegistry 构造函数分析（76-136行）](#一zookeeperregistry-构造函数分析76-136行)
  - [1.1 获取配置属性（第 77 行）](#11-获取配置属性第-77-行)
  - [1.2 创建指数退避重试策略（第 97-100 行）](#12-创建指数退避重试策略第-97-100-行)
  - [1.3 构建 CuratorFramework 客户端（第 102-116 行）](#13-构建-curatorframework-客户端第-102-116-行)
  - [1.4 配置 ACL 权限认证（可选，第 119-134 行）](#14-配置-acl-权限认证可选第-119-134-行)
  - [1.5 构建客户端实例（第 135 行）](#15-构建客户端实例第-135-行)
- [二、start() 方法分析（143-160行）](#二start-方法分析143-160行)
  - [2.1 性能监控（第 144 行）](#21-性能监控第-144-行)
  - [2.2 启动客户端（第 145 行）](#22-启动客户端第-145-行)
  - [2.3 同步等待连接建立（第 148-149 行）](#23-同步等待连接建立第-148-149-行)
  - [2.4 连接失败处理（第 150-153 行）](#24-连接失败处理第-150-153-行)
  - [2.5 成功日志记录（第 155-156 行）](#25-成功日志记录第-155-156-行)
  - [2.6 中断异常处理（第 157-159 行）](#26-中断异常处理第-157-159-行)
  - [2.7 完整执行流程图](#27-完整执行流程图)
- [三、设计要点总结](#三设计要点总结)
  - [3.1 构造函数设计](#31-构造函数设计)
  - [3.2 start() 方法设计](#32-start-方法设计)
- [四、配置示例](#四配置示例)
- [五、关键概念对比](#五关键概念对比)
- [六、总结](#六总结)

---

## 一、ZookeeperRegistry 构造函数分析（76-136行）

构造函数负责初始化 Zookeeper 客户端（基于 Apache Curator），配置连接参数、重试策略和权限认证。

### 1.1 获取配置属性（第 77 行）

```java
properties = registryProperties.getZookeeper();
```

- 从 `ZookeeperRegistryProperties` 中提取 `ZookeeperProperties`
- 包含连接字符串、命名空间、超时时间、重试策略等配置

### 1.2 创建指数退避重试策略（第 97-100 行）

```java
final ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(
        (int) properties.getRetryPolicy().getBaseSleepTime().toMillis(),
        properties.getRetryPolicy().getMaxRetries(),
        (int) properties.getRetryPolicy().getMaxSleep().toMillis());
```

**参数说明：**
- `baseSleepTimeMs`：初始等待时间（默认 1 秒）
- `maxRetries`：最大重试次数（默认 3 次）
- `maxSleepMs`：最大等待时间（默认 3 秒）

**重试机制详解：**

重试间隔按指数增长，直到达到 `maxSleepMs`。举例说明：

假设配置：
- `baseSleepTime`: 1000ms
- `maxRetries`: 5
- `maxSleep`: 3000ms

重试过程：

| 重试次数 | 计算公式 | 计算值 | 实际等待时间 | 说明 |
|---------|---------|--------|-------------|------|
| 第1次重试 | 1000ms × 2⁰ = 1000ms | 1000ms | **1秒** | 初始等待时间 |
| 第2次重试 | 1000ms × 2¹ = 2000ms | 2000ms | **2秒** | 指数增长 |
| 第3次重试 | 1000ms × 2² = 4000ms | 4000ms | **3秒** | 达到 maxSleep，被限制为 3秒 |
| 第4次重试 | - | - | **3秒** | 继续保持 maxSleep |
| 第5次重试 | - | - | **3秒** | 继续保持 maxSleep |

**核心逻辑：**
- 公式：`waitTime = baseSleepTime × 2^(retryCount-1)`
- 限制：`实际等待时间 = min(计算值, maxSleepMs)`
- 作用：避免频繁重试，并防止等待时间过长

### 1.3 构建 CuratorFramework 客户端（第 102-116 行）

```java
CuratorFrameworkFactory.Builder builder =
        CuratorFrameworkFactory.builder()
                .connectString(properties.getConnectString())  // IP:PORT 或 IP1:PORT1,IP2:PORT2
                .retryPolicy(retryPolicy)
                .namespace(properties.getNamespace())  // 命名空间，默认 dolphinscheduler
                .sessionTimeoutMs(DurationUtils.toMillisInt(properties.getSessionTimeout()))  // 会话超时（默认 60 秒）
                .connectionTimeoutMs(DurationUtils.toMillisInt(properties.getConnectionTimeout()));  // 连接超时（默认 15 秒）
```

**配置说明：**

1. **`.connectString()`**：ZooKeeper 连接字符串
    - 单节点：`localhost:2181`
    - 集群：`zoo1:2181,zoo2:2182,zoo3:2183`

2. **`.retryPolicy(retryPolicy)`**：设置重试策略

3. **`.namespace()`**：命名空间，所有操作都在该命名空间下进行

4. **`.sessionTimeoutMs()`**：会话超时时间，连接断开后会话保持时长

5. **`.connectionTimeoutMs()`**：连接超时时间，建立连接的最大等待时间

**集群连接机制：**

当 `connectString` 配置为集群地址时（如 `zoo1:2181,zoo2:2182,zoo3:2183`），`CuratorFramework` 的行为：

- **不会同时连接所有节点**：只维护一个活跃连接
- **服务器列表**：维护所有服务器的列表
- **自动故障转移**：当前连接失败时，自动尝试列表中的下一个节点
- **会话保持**：通过 ZooKeeper 的会话机制，在连接断开后的一段时间内（sessionTimeout）保持会话，重连后可以恢复

**连接流程：**
```
连接字符串: "zoo1:2181,zoo2:2182,zoo3:2183"
    ↓
解析为服务器列表: [zoo1:2181, zoo2:2182, zoo3:2183]
    ↓
随机选择一个服务器（或按顺序）尝试连接
    ↓
如果连接成功 → 保持该连接，后续操作都通过这个连接
    ↓
如果连接失败 → 自动尝试列表中的下一个服务器
    ↓
如果当前连接断开 → 从服务器列表中重新选择并重连
```

### 1.4 配置 ACL 权限认证（可选，第 119-134 行）

```java
final String digest = properties.getDigest();
if (!Strings.isNullOrEmpty(digest)) {
    builder.authorization("digest", digest.getBytes(StandardCharsets.UTF_8))
            .aclProvider(new ACLProvider() {
                @Override
                public List<ACL> getDefaultAcl() {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }

                @Override
                public List<ACL> getAclForPath(final String path) {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }
            });
}
```

**digest 配置说明：**

在 YAML 配置中：
- `digest: ~` 表示 `null`（空值），**不启用认证**
- `digest: "admin:password123"` 表示启用 Digest 认证，格式为 `用户名:密码`

**配置对比：**

| 配置方式 | YAML 值 | Java 中的值 | `isNullOrEmpty()` 结果 | 是否启用认证 |
|---------|---------|-------------|----------------------|-------------|
| `digest: ~` | `~` | `null` | `true` | ❌ 不启用 |
| `digest: ""` | `""` | `""` | `true` | ❌ 不启用 |
| `digest: null` | `null` | `null` | `true` | ❌ 不启用 |
| `digest: admin:password123` | `"admin:password123"` | `"admin:password123"` | `false` | ✅ 启用 |
| 不配置 `digest` | - | `null`（默认） | `true` | ❌ 不启用 |

**ACL 配置：**
- 使用 `ZooDefs.Ids.CREATOR_ALL_ACL`：创建者拥有所有权限（READ、WRITE、CREATE、DELETE、ADMIN）
- 适用于开发环境和内网环境（生产环境建议使用更细粒度的 ACL 控制）

### 1.5 构建客户端实例（第 135 行）

```java
client = builder.build();
```

- 构建 `CuratorFramework` 实例（此时**未连接**）
- 实际连接在 `start()` 方法中触发

## 二、start() 方法分析（143-160行）

`start()` 方法负责启动 CuratorFramework 客户端并确保连接建立，采用**异步启动 + 同步等待**的模式。

### 2.1 性能监控（第 144 行）

```java
final StopWatch stopWatch = StopWatch.createStarted();
```

- 创建并启动计时器（Apache Commons Lang3）
- 记录启动耗时，用于监控和日志

### 2.2 启动客户端（第 145 行）

```java
client.start();
```

**关键点：**
- **异步非阻塞调用**：后台开始连接过程，不会等待连接成功就返回
- 需要在后续使用 `blockUntilConnected()` 同步等待连接建立

**执行流程：**
```
client.start()
    ↓
后台线程开始尝试连接 ZooKeeper
    ↓
方法立即返回（不阻塞）
    ↓
需要等待连接建立才能进行后续操作
```

### 2.3 同步等待连接建立（第 148-149 行）

```java
if (!client.blockUntilConnected(DurationUtils.toMillisInt(properties.getBlockUntilConnected()),
        MILLISECONDS)) {
```

**作用：**
- **阻塞当前线程**，直到连接建立或超时
- `blockUntilConnected(timeout, unit)` 返回值：
    - `true`：在超时时间内连接成功
    - `false`：超时仍未连接成功

**参数说明：**
- `properties.getBlockUntilConnected()`：默认 15 秒（配置中的 `block-until-connected`）
- `MILLISECONDS`：时间单位
- `DurationUtils.toMillisInt()`：将 `Duration` 转换为毫秒整数

**为什么需要这个等待：**
- `client.start()` 是异步的，不能立即使用客户端
- 需要确保连接建立后再返回，避免后续操作失败

### 2.4 连接失败处理（第 150-153 行）

```java
client.close();
throw new RegistryException(
    "zookeeper connect failed to: " + properties.getConnectString() + " in : "
            + properties.getBlockUntilConnected() + "ms");
```

**逻辑：**
- 如果 `blockUntilConnected()` 返回 `false`（超时未连接）
- **清理资源**：关闭客户端，避免资源泄漏
- **抛出异常**：告知调用者启动失败，包含连接字符串和超时时间

**为什么先 close 再抛异常：**
- **资源清理**：已启动但未成功连接的客户端需要关闭
- **避免泄漏**：防止持有未使用的连接资源
- **明确失败**：让调用者知道启动失败，不会继续使用

### 2.5 成功日志记录（第 155-156 行）

```java
stopWatch.stop();
log.info("ZookeeperRegistry started at: {}/ms", stopWatch.getTime());
```

- 停止计时器
- 记录启动耗时日志，便于监控和性能分析

示例日志输出：
```
INFO  ZookeeperRegistry started at: 1234/ms
```

### 2.6 中断异常处理（第 157-159 行）

```java
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RegistryException("Zookeeper registry start failed", e);
}
```

**处理说明：**
- `blockUntilConnected()` 可能抛出 `InterruptedException`（等待过程中被中断）
- `Thread.currentThread().interrupt()`：恢复中断标志（Java 最佳实践）
- 重新抛出为 `RegistryException`，统一异常类型

**为什么要恢复中断标志：**
- Java 线程中断机制：捕获 `InterruptedException` 后应恢复标志
- 保持中断状态：让上层代码知道线程被中断
- 符合 Java 并发编程规范

### 2.7 完整执行流程图

```
start() 方法调用
    ↓
[1] 启动 StopWatch 计时
    ↓
[2] client.start() - 异步启动连接
    ├─→ 立即返回（不阻塞）
    └─→ 后台线程开始连接 ZooKeeper
    ↓
[3] blockUntilConnected() - 阻塞等待
    ├─→ 成功连接 → 返回 true
    │   ↓
    │   [4] 停止计时，记录日志
    │   ↓
    │   [5] 方法返回（成功）
    │
    └─→ 超时未连接 → 返回 false
        ↓
        [6] 关闭客户端，抛出异常
        ↓
        [7] 方法抛出异常（失败）
    
    或抛出 InterruptedException
        ↓
        [8] 恢复中断标志，抛出 RegistryException
```

## 三、设计要点总结

### 3.1 构造函数设计

1. **延迟连接**：构造函数只配置，不建立连接，符合"懒加载"原则
2. **命名空间隔离**：使用命名空间避免路径冲突
3. **可选认证**：支持 Digest 认证，可根据环境灵活配置
4. **指数退避重试**：提高网络不稳定时的成功率
5. **超时控制**：区分会话超时和连接超时，便于调优

### 3.2 start() 方法设计

1. **异步启动 + 同步等待**：
    - `start()` 异步启动，`blockUntilConnected()` 同步等待
    - 既提高了启动效率，又保证了连接建立

2. **超时控制**：
    - 通过 `blockUntilConnected` 控制最大等待时间
    - 避免无限等待，快速失败

3. **资源清理**：
    - 连接失败时主动 `close()`，避免资源泄漏

4. **异常处理**：
    - 正确处理 `InterruptedException`
    - 统一异常类型（`RegistryException`）

5. **可观测性**：
    - 使用 `StopWatch` 记录启动耗时
    - 日志包含关键信息（连接字符串、超时时间）

## 四、配置示例

```yaml
registry:
  type: zookeeper
  zookeeper:
    namespace: dolphinscheduler
    connect-string: localhost:2181  # 单节点
    # connect-string: zoo1:2181,zoo2:2182,zoo3:2183  # 集群配置
    digest: ~  # 不启用认证（~ 表示 null）
    # digest: admin:password123  # 启用认证
    session-timeout: 60s
    connection-timeout: 15s
    block-until-connected: 15s  # 最多等待 15 秒建立连接
    retry-policy:
      base-sleep-time: 1s
      max-retries: 3
      max-sleep: 3s
```

## 五、关键概念对比

| 概念 | 说明 | 默认值 |
|------|------|--------|
| `sessionTimeout` | 会话超时时间，连接断开后会话保持时长 | 60秒 |
| `connectionTimeout` | 连接超时时间，建立连接的最大等待时间 | 15秒 |
| `blockUntilConnected` | 启动时阻塞等待连接建立的最大时间 | 15秒 |
| `baseSleepTime` | 重试策略的初始等待时间 | 1秒 |
| `maxSleep` | 重试策略的最大等待时间 | 3秒 |
| `maxRetries` | 重试策略的最大重试次数 | 3次 |

## 六、总结

本文档详细分析了 `ZookeeperRegistry` 在启动时与 ZooKeeper 建立连接的过程，主要包括：

1. **构造函数（76-136行）**：配置客户端参数，包括重试策略、连接字符串、命名空间、超时时间和可选的 ACL 认证
2. **start() 方法（143-160行）**：异步启动客户端，同步等待连接建立，处理失败情况和异常

关键要点：
- 构造函数只配置不连接，实现延迟连接
- `start()` 方法采用异步启动 + 同步等待的模式
- 支持集群配置，自动故障转移
- 完善的资源清理和异常处理机制
- 提供可观测性支持（日志和计时）