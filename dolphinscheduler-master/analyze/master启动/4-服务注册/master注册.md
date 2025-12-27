# Master 服务注册机制详细分析

## 目录

1. [1. 概述](#1-概述)
   1. [1.1 核心组件](#11-核心组件)
2. [2. 心跳任务分析](#2-心跳任务分析)
   1. [2.1 调用链路](#21-调用链路)
   2. [2.2 设计模式：模板方法模式](#22-设计模式模板方法模式)
   3. [2.3 心跳数据内容](#23-心跳数据内容)
   4. [2.4 写入逻辑](#24-写入逻辑)
   5. [2.5 服务负载保护](#25-服务负载保护)
3. [3. 为什么 MasterHeartBeatTask 不使用 Spring 依赖注入？](#3-为什么-masterheartbeattask-不使用-spring-依赖注入)
   1. [3.1 问题背景](#31-问题背景)
   2. [3.2 原因分析](#32-原因分析)
   3. [3.3 对比：SystemEventBusFireWorker 为什么可以用 Spring 注入？](#33-对比systemeventbusfireworker-为什么可以用-spring-注入)
   4. [3.4 总结](#34-总结)
4. [4. 服务注册流程](#4-服务注册流程)
   1. [4.1 registry() 方法详细分析](#41-registry-方法详细分析)
   2. [4.2 完整执行流程](#42-完整执行流程)
5. [5. RegistryClient 架构](#5-registryclient-架构)
   1. [5.1 分层架构](#51-分层架构)
   2. [5.2 设计模式](#52-设计模式)
   3. [5.3 初始化流程](#53-初始化流程)
   4. [5.4 核心方法](#54-核心方法)
   5. [5.5 注册中心路径结构](#55-注册中心路径结构)
6. [6. 服务注销流程](#6-服务注销流程)
   1. [6.1 deregister() 方法分析](#61-deregister-方法分析)
   2. [6.2 调用时机](#62-调用时机)
   3. [6.3 资源清理顺序](#63-资源清理顺序)
   4. [6.4 异常处理](#64-异常处理)
7. [7. 异常处理机制](#7-异常处理机制)
   1. [7.1 setRegistryStoppable(this) 机制](#71-setregistrystoppablethis-机制)
   2. [7.2 使用场景](#72-使用场景)
   3. [7.3 完整调用链](#73-完整调用链)
   4. [7.4 设计优势](#74-设计优势)
   5. [7.5 为什么需要这个机制？](#75-为什么需要这个机制)
   6. [7.6 时序图](#76-时序图)
8. [8. 总结](#8-总结)
   1. [8.1 核心机制](#81-核心机制)
   2. [8.2 设计特点](#82-设计特点)
   3. [8.3 关键设计决策](#83-关键设计决策)
   4. [8.4 最佳实践](#84-最佳实践)
   5. [8.5 注意事项](#85-注意事项)
   6. [8.6 相关配置](#86-相关配置)
9. [9. 参考资料](#9-参考资料)

---

## 1. 概述

Master 服务注册是 DolphinScheduler 集群管理的关键机制，主要包括：

- **服务注册**：Master 节点启动时注册到注册中心
- **心跳维护**：定期更新心跳数据，保持服务状态
- **服务注销**：服务关闭时从注册中心注销
- **异常处理**：连接断开、故障转移等异常情况的处理

### 1.1 核心组件

```
MasterServer
    └─→ MasterRegistryClient
            ├─→ MasterHeartBeatTask (心跳任务)
            └─→ RegistryClient (注册中心客户端)
                    └─→ Registry (SPI接口)
                            ├─→ ZookeeperRegistry
                            ├─→ JdbcRegistry
                            └─→ EtcdRegistry
```

---

## 2. 心跳任务分析

### 2.1 调用链路

```
MasterServer.initialized() (第149行)
  └─> masterRegistryClient.start()
       ├─> 创建 MasterHeartBeatTask (第68-70行)
       ├─> registry() (第72行)
       │    ├─> 检查服务负载状态，如果BUSY则等待直到NORMAL (第110-114行)
       │    ├─> 注册到注册中心 (第118行)
       │    ├─> 验证注册是否成功 (第122-134行)
       │    └─> masterHeartBeatTask.start() (第142行)
       └─> 添加连接状态监听器 (第73行)
```

### 2.2 设计模式：模板方法模式

`BaseHeartBeatTask` 使用模板方法模式：

- **模板方法**：`run()` 定义执行骨架（第61-82行）
- **抽象方法**：子类实现
    - `getHeartBeat()`：获取心跳数据
    - `writeHeartBeat()`：写入注册中心

#### 2.2.1 执行机制

```java
// BaseHeartBeatTask.run()
while (runningFlag) {
    1. 获取心跳数据：getHeartBeat()
    2. 判断是否需要写入：
       - 距离上次写入时间 >= heartBeatInterval（默认由配置决定）
       - 或者服务状态发生变化（NORMAL <-> BUSY）
    3. 如果需要，调用 writeHeartBeat() 写入注册中心
    4. 休眠 1 秒（DEFAULT_HEARTBEAT_SCAN_INTERVAL）
}
```

**关键点**：
- **扫描间隔**：固定 1 秒
- **写入间隔**：由 `masterConfig.getMaxHeartbeatInterval()` 控制
- **状态变化时立即写入**：确保状态变更及时通知

### 2.3 心跳数据内容

`MasterHeartBeatTask.getHeartBeat()` 包含以下信息：

- **系统指标**：
    - `cpuUsage`：系统 CPU 使用率
    - `memoryUsage`：系统内存使用率
    - `diskUsage`：磁盘使用率
- **JVM 指标**：
    - `jvmCpuUsage`：JVM CPU 使用率
    - `jvmMemoryUsage`：JVM 内存使用率
- **服务信息**：
    - `startupTime`：服务启动时间
    - `reportTime`：报告时间
    - `processId`：进程 ID
    - `serverStatus`：服务状态（NORMAL/BUSY）
    - `host`：主机地址
    - `port`：监听端口
    - `isCoordinator`：是否为协调者

### 2.4 写入逻辑

```java
// MasterHeartBeatTask.writeHeartBeat()
// 写入前检查
String failoverNodePath = RegistryUtils.getFailoveredNodePath(masterHeartBeat);
if (registryClient.exists(failoverNodePath)) {
    // 如果存在，说明该节点已被故障转移，停止服务
    registryClient.getStoppable().stop(...);
    return;
}

// 写入操作
registryClient.persistEphemeral(heartBeatPath, masterHeartBeatJson);
```

**关键点**：
- 写入前检查 failover 节点，避免重复注册
- 使用 `persistEphemeral()` 创建临时节点（连接断开自动删除）
- 更新心跳计数指标

### 2.5 服务负载保护

注册时的负载检查（`MasterRegistryClient.registry()` 第110-114行）：

```java
while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
    log.warn("Master node is BUSY: {}", heartBeat);
    heartBeat = masterHeartBeatTask.getHeartBeat();
    ThreadUtils.sleep(SLEEP_TIME_MILLIS); // 等待1秒后重试
}
```

**设计目的**：
- 如果服务处于 BUSY 状态，会等待直到状态变为 NORMAL 再注册
- 避免高负载时注册到注册中心，影响集群稳定性

---

## 3. 为什么 MasterHeartBeatTask 不使用 Spring 依赖注入？

### 3.1 问题背景

在 `MasterRegistryClient.start()` 方法中，`MasterHeartBeatTask` 是通过构造函数手动创建的：

```java
this.masterHeartBeatTask = new MasterHeartBeatTask(
    masterConfig, metricsProvider, registryClient, masterCoordinator);
```

而不是使用 Spring 的 `@Autowired` 依赖注入。

### 3.2 原因分析

#### 3.2.1 需要在注册前创建，但延迟启动线程

查看 `registry()` 方法的执行顺序：

```java
void registry() {
    // 第104行：需要先创建心跳任务，获取心跳数据用于负载检查
    MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();
    
    // 第110-114行：使用心跳数据检查服务负载
    while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
        heartBeat = masterHeartBeatTask.getHeartBeat();
        ...
    }
    
    // 第118行：使用心跳数据注册到注册中心
    registryClient.persistEphemeral(..., masterHeartBeatTask.getHeartBeat());
    
    // ... 验证注册成功 ...
    
    // 第142行：最后才启动心跳线程
    masterHeartBeatTask.start();
}
```

**关键点**：
- 注册前需要调用 `getHeartBeat()` 获取数据
- 线程启动在注册验证完成后
- 如果使用 Spring 注入，Bean 会在容器初始化时创建，但此时可能未准备好

#### 3.2.2 生命周期需要精确控制

- **创建时机**：在 `start()` 方法中按需创建
- **启动时机**：注册成功并验证后启动
- **关闭时机**：在 `close()` 中调用 `shutdown()`

如果使用 Spring 管理：
- Bean 会在容器初始化时创建，时机不对
- 线程启动时机难以精确控制
- 关闭需要配合 Spring 的销毁流程

#### 3.2.3 进程ID需要在创建时获取

```java
public MasterHeartBeatTask(...) {
    super("MasterHeartBeatTask", masterConfig.getMaxHeartbeatInterval().toMillis());
    ...
    this.processId = OSUtils.getProcessID(); // 需要在创建时获取
}
```

`processId` 需要在实例创建时获取，而不是 Spring 初始化时。

#### 3.2.4 与注册流程紧密耦合

心跳任务的创建和启动是注册流程的一部分，需要在 `start()` 中按顺序执行：
1. 创建心跳任务
2. 获取心跳数据检查负载
3. 注册到注册中心
4. 验证注册成功
5. 启动心跳线程

### 3.3 对比：SystemEventBusFireWorker 为什么可以用 Spring 注入？

`SystemEventBusFireWorker` 使用 `@Component` 注解，依赖通过 `@Autowired` 注入，但它的 `start()` 方法也是手动调用的。

**区别在于**：
- `SystemEventBusFireWorker` 不需要在启动前获取数据
- 它的创建和启动可以分离，Spring 管理创建，业务代码控制启动
- `MasterHeartBeatTask` 需要在启动前就使用（获取心跳数据），所以必须在业务代码中创建

### 3.4 总结

`MasterHeartBeatTask` 不使用 Spring 依赖注入的原因：

1. **需要在注册前获取心跳数据，但线程要延迟启动**
2. **生命周期需要精确控制，与注册流程耦合**
3. **进程ID等值需要在创建时获取**
4. **线程对象的生命周期应由业务代码管理，而非 Spring 容器**

这是一种常见的设计模式：对于需要精确控制生命周期的线程对象，通常采用手动创建而非 Spring 管理。

---

## 4. 服务注册流程

### 4.1 registry() 方法详细分析

#### 4.1.1 阶段一：负载检查（第127-138行）

```java
// 1. 获取心跳数据
MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();

// 2. 负载保护循环
while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
    log.warn("Master node is BUSY: {}", heartBeat);
    heartBeat = masterHeartBeatTask.getHeartBeat();  // 重新获取
    ThreadUtils.sleep(SLEEP_TIME_MILLIS);  // 等待1秒
}
```

**目的**：
- 注册前检查服务负载状态
- 如果服务处于 BUSY 状态，等待直到状态变为 NORMAL
- 避免高负载时注册到注册中心

#### 4.1.2 阶段二：注册到注册中心（第140-143行）

```java
// 1. 先删除旧节点（如果存在）
registryClient.remove(masterRegistryPath);

// 2. 创建临时节点并写入心跳数据
registryClient.persistEphemeral(masterRegistryPath, 
    JSONUtils.toJsonString(masterHeartBeatTask.getHeartBeat()));
```

**关键点**：
- `remove()`：先删除旧节点，避免重复注册
- `persistEphemeral()`：创建临时节点（连接断开自动删除）
- 路径格式：`/nodes/master/ip:port`
- 值：心跳数据的 JSON 字符串

#### 4.1.3 阶段三：验证注册成功（第145-158行）

```java
int checkCount = 0;
while (!registryClient.checkNodeExists(NetUtils.getHost(), RegistryNodeType.MASTER)) {
    checkCount++;
    // 记录警告日志
    ThreadUtils.sleep(SLEEP_TIME_MILLIS);
    // 超时保护：最多检查30次
    if (checkCount > 30) {
        throw new RegistryException("Master node cannot be found in registry after multiple checks");
    }
}
```

**目的**：
- 验证节点是否已成功写入注册中心
- 处理可能的同步延迟问题
- 超时保护：最多检查30次（约30秒）

#### 4.1.4 阶段四：等待故障转移清理（第160-163行）

```java
// sleep 1s, waiting master failover remove
ThreadUtils.sleep(SLEEP_TIME_MILLIS);
```

**目的**：
- 等待可能的故障转移清理完成
- 避免与故障转移流程冲突

#### 4.1.5 阶段五：启动心跳任务（第165-167行）

```java
masterHeartBeatTask.start();
```

**目的**：
- 启动心跳线程，定期更新注册中心的心跳数据

### 4.2 完整执行流程

```
1. 负载检查 → 等待服务负载正常
2. 注册节点 → 删除旧节点，创建临时节点
3. 验证注册 → 循环检查直到节点可见
4. 等待清理 → 等待故障转移清理完成
5. 启动心跳 → 启动定期更新心跳数据的线程
```

---

## 5. RegistryClient 架构

### 5.1 分层架构

```
应用层 (Application Layer)
    ├── MasterRegistryClient
    ├── WorkerRegistryClient
    └── AlertRegistryClient
            │
            ↓ 依赖注入
    ┌───────────────────────┐
    │   RegistryClient       │  (门面类/Facade)
    │   (统一API封装)        │
    └───────────────────────┘
            │
            ↓ 组合关系
    ┌───────────────────────┐
    │   Registry (SPI接口)   │
    └───────────────────────┘
            │
    ┌───────┼───────┬──────────┐
    │       │       │          │
    ↓       ↓       ↓          ↓
ZookeeperRegistry  JdbcRegistry  EtcdRegistry  (其他实现)
```

### 5.2 设计模式

- **门面模式**：`RegistryClient` 封装 `Registry`，提供统一 API
- **SPI 机制**：`Registry` 为 SPI 接口，支持插件化实现
- **策略模式**：不同 `Registry` 实现可替换
- **依赖注入**：通过 Spring 管理生命周期

### 5.3 初始化流程

#### 5.3.1 Spring Boot 自动配置流程

```
Spring Boot 启动
    │
    ↓
扫描 @Configuration 类
    │
    ├─→ RegistryConfiguration (registry-api)
    │   └─→ @Bean RegistryClient(Registry registry)
    │       └─→ 等待 Registry Bean 注入
    │
    └─→ 根据配置选择 Registry 实现
        │
        ├─→ registry.type=zookeeper
        │   └─→ ZookeeperRegistryAutoConfiguration
        │       └─→ @Bean ZookeeperRegistry
        │           └─→ new ZookeeperRegistry()
        │           └─→ registry.start()
        │
        ├─→ registry.type=jdbc
        │   └─→ JdbcRegistryAutoConfiguration
        │       ├─→ @Bean IJdbcRegistryServer
        │       ├─→ @Bean JdbcRegistry
        │       │   └─→ new JdbcRegistry()
        │       │   └─→ registry.start()
        │       └─→ @Bean SqlSessionFactory
        │
        └─→ registry.type=etcd
            └─→ EtcdRegistryAutoConfiguration
                └─→ @Bean EtcdRegistry
                    └─→ new EtcdRegistry()
```

#### 5.3.2 初始化顺序

```java
// 1. Registry 实现类初始化 (根据配置自动选择)
@Configuration
@ConditionalOnProperty(prefix = "registry", name = "type", havingValue = "zookeeper")
public class ZookeeperRegistryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(value = Registry.class)
    public Registry zookeeperRegistry(ZookeeperRegistryProperties properties) {
        ZookeeperRegistry registry = new ZookeeperRegistry(properties);
        registry.start();  // 启动连接
        return registry;
    }
}

// 2. RegistryClient 初始化
@Configuration
public class RegistryConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RegistryClient registryClient(Registry registry) {
        return new RegistryClient(registry);  // 注入 Registry
    }
}

// 3. RegistryClient 构造函数执行
public RegistryClient(Registry registry) {
    this.registry = registry;
    // 初始化注册中心路径结构
    if (!registry.exists(RegistryNodeType.MASTER.getRegistryPath())) {
        registry.put(RegistryNodeType.MASTER.getRegistryPath(), EMPTY, false);
    }
    // ... 创建其他必要路径
    cleanHistoryFailoverFinishedNodes();  // 清理历史数据
}
```

### 5.4 核心方法

#### 5.4.1 persistEphemeral(String key, String value)

```java
public void persistEphemeral(String key, String value) {
    registry.put(key, value, true);  // deleteOnDisconnect = true
}
```

**不同实现的差异**：
- **Zookeeper**：使用 `CreateMode.EPHEMERAL`（临时节点）
- **JDBC**：使用 `DataType.EPHEMERAL`（标记为临时，由服务端管理）
- **Etcd**：使用 Lease（租约），断开后自动过期删除

#### 5.4.2 checkNodeExists(String host, RegistryNodeType nodeType)

```java
public boolean checkNodeExists(String host, RegistryNodeType nodeType) {
    return getServerMaps(nodeType).keySet()
            .stream()
            .anyMatch(it -> it.contains(host));
}
```

**执行流程**：
1. `getServerMaps(nodeType)` → 获取所有服务器映射
2. `getServerNodes(nodeType)` → 获取子节点列表
3. `getChildrenKeys(nodeType.getRegistryPath())` → 调用 `registry.children()`
4. 遍历每个服务器节点，获取其心跳数据
5. 检查是否有节点包含指定的 host

### 5.5 注册中心路径结构

```
/dolphinscheduler (根路径，由配置决定)
    ├── /nodes
    │   ├── /master
    │   │   ├── /192.168.1.100:5678  (临时节点，值为心跳JSON)
    │   │   └── /192.168.1.101:5678
    │   ├── /worker
    │   └── /alert-server
    ├── /lock
    │   ├── /global-master-failover
    │   └── /master-failover
    └── /nodes/failover-finish-nodes
```

---

## 6. 服务注销流程

### 6.1 deregister() 方法分析

#### 6.1.1 方法执行流程

```java
public void deregister() {
    try {
        // 步骤1: 删除注册中心节点
        registryClient.remove(masterConfig.getMasterRegistryPath());
        log.info("Master node : {} unRegistry to register center.", masterConfig.getMasterAddress());
        
        // 步骤2: 关闭心跳任务
        if (masterHeartBeatTask != null) {
            masterHeartBeatTask.shutdown();
        }
        
        // 步骤3: 关闭注册中心连接
        registryClient.close();
    } catch (Exception e) {
        log.error("MasterServer remove registry path exception ", e);
    }
}
```

#### 6.1.2 执行步骤详解

**步骤1：删除注册中心节点**

```java
registryClient.remove(masterConfig.getMasterRegistryPath());
```

- **作用**：从注册中心删除当前 Master 节点
- **路径格式**：`/nodes/master/ip:port`
- **目的**：通知其他节点该 Master 已下线

**步骤2：关闭心跳任务**

```java
if (masterHeartBeatTask != null) {
    masterHeartBeatTask.shutdown();
}
```

- **作用**：停止心跳线程
- **实现**：设置 `runningFlag = false`，线程自然退出

**步骤3：关闭注册中心连接**

```java
registryClient.close();
```

- **作用**：关闭与注册中心的连接，释放相关资源
- **实现链**：
  ```
  RegistryClient.close()
      └─→ Registry.close()
          ├─→ ZookeeperRegistry.close()
          │   ├─→ 关闭所有 TreeCache
          │   └─→ 关闭 CuratorFramework 客户端
          │
          ├─→ JdbcRegistry.close()
          │   ├─→ 停止服务端线程池
          │   ├─→ 清理客户端注册信息
          │   └─→ 清理内存缓存
          │
          └─→ EtcdRegistry.close()
              └─→ 关闭 Etcd 客户端连接
  ```

### 6.2 调用时机

#### 6.2.1 正常关闭流程

```
MasterServer.close(cause)
    │
    ├─→ 设置停止标志 (ServerLifeCycleManager.toStopped())
    ├─→ 等待3秒 (让线程安静停止)
    ├─→ 关闭线程池
    └─→ try-with-resources 自动关闭
            │
            └─→ MasterRegistryClient.close()  (AutoCloseable)
                    │
                    ├─→ masterHeartBeatTask.shutdown()  (先停止心跳)
                    └─→ if (registryClient.isConnected())
                            └─→ deregister()  (如果连接存在才注销)
                                    │
                                    ├─→ registryClient.remove()  (删除节点)
                                    ├─→ masterHeartBeatTask.shutdown()  (再次确认关闭)
                                    └─→ registryClient.close()  (关闭连接)
```

#### 6.2.2 调用场景

1. **正常关闭**（`@PreDestroy`）
2. **JVM 关闭钩子**
3. **连接断开**（`MasterConnectionStateListener`）
4. **故障转移检测**

### 6.3 资源清理顺序

```
1. 停止心跳任务
   └─→ 设置 runningFlag = false
   └─→ 心跳线程自然退出

2. 删除注册中心节点
   └─→ 通知其他节点该 Master 已下线
   └─→ 触发其他节点的监听事件

3. 关闭注册中心连接
   └─→ 关闭底层连接（Zookeeper/Etcd/数据库连接池）
   └─→ 释放资源（TreeCache、线程池等）
```

**为什么先删除节点再关闭连接？**
- 先删除节点，其他节点能及时感知下线
- 再关闭连接，避免临时节点因连接断开自动删除的延迟

### 6.4 异常处理

```java
try {
    // 执行注销操作
} catch (Exception e) {
    log.error("MasterServer remove registry path exception ", e);
    // 注意：异常被捕获，不会抛出
}
```

**设计考虑**：
- 关闭阶段尽量不抛出异常，保证流程完成
- 记录错误日志便于排查

---

## 7. 异常处理机制

### 7.1 setRegistryStoppable(this) 机制

#### 7.1.1 代码含义

```java
// MasterServer.java 第150行
this.masterRegistryClient.setRegistryStoppable(this);
```

**含义**：
- 将 `MasterServer` 实例（实现了 `IStoppable`）注册到 `RegistryClient`
- 当注册中心检测到异常情况（连接断开、故障转移等）时，可以回调 `MasterServer.stop()` 来停止服务

#### 7.1.2 设计模式：回调/观察者模式

**接口定义**：

```java
// IStoppable.java
public interface IStoppable {
    /**
     * Stop this service.
     * @param cause why stopping
     */
    void stop(String cause);
}
```

**MasterServer 实现接口**：

```java
// MasterServer.java 第68行
public class MasterServer implements IStoppable {
    
    @Override
    public void stop(String cause) {
        close(cause);
        System.exit(1);  // 确保退出
    }
}
```

**注册回调**：

```java
// MasterServer.initialized() 第150行
this.masterRegistryClient.setRegistryStoppable(this);

// MasterRegistryClient.setRegistryStoppable()
public void setRegistryStoppable(IStoppable stoppable) {
    registryClient.setStoppable(stoppable);  // 传递给 RegistryClient
}

// RegistryClient.setStoppable()
public void setStoppable(IStoppable stoppable) {
    this.stoppable = stoppable;  // 保存引用
}
```

### 7.2 使用场景

#### 7.2.1 场景1：注册中心连接断开

```java
// MasterConnectionStateListener.java
@Override
public void onUpdate(ConnectionState state) {
    switch (state) {
        case DISCONNECTED:
            // 连接断开时，主动停止 MasterServer
            registryClient.getStoppable().stop(
                "Master disconnected from registry, will stop myself");
            break;
        // ...
    }
}
```

**触发流程**：
```
注册中心连接断开
    ↓
ConnectionStateListener.onUpdate(DISCONNECTED)
    ↓
registryClient.getStoppable().stop("...")
    ↓
MasterServer.stop("Master disconnected from registry, will stop myself")
    ↓
MasterServer.close()
    ↓
System.exit(1)
```

#### 7.2.2 场景2：检测到故障转移节点

```java
// MasterHeartBeatTask.writeHeartBeat()
@Override
public void writeHeartBeat(final MasterHeartBeat masterHeartBeat) {
    final String failoverNodePath = RegistryUtils.getFailoveredNodePath(masterHeartBeat);
    
    // 检测到故障转移节点存在
    if (registryClient.exists(failoverNodePath)) {
        log.warn("The master: {} is under {}, means it has been failover will close myself",
                masterHeartBeat, failoverNodePath);
        
        // 主动停止服务
        registryClient.getStoppable().stop(
            "The master exist: " + failoverNodePath + 
            ", means it has been failover will close myself");
        return;
    }
    // ... 正常写入心跳
}
```

**触发流程**：
```
心跳任务检测到故障转移节点
    ↓
registryClient.exists(failoverNodePath) == true
    ↓
registryClient.getStoppable().stop("...")
    ↓
MasterServer.stop("The master exist: ... failover will close myself")
    ↓
MasterServer.close()
    ↓
System.exit(1)
```

### 7.3 完整调用链

```
初始化阶段：
MasterServer.initialized()
    └─→ masterRegistryClient.setRegistryStoppable(this)
            └─→ registryClient.setStoppable(MasterServer实例)
                    └─→ RegistryClient.stoppable = MasterServer实例

异常检测阶段：
注册中心异常事件
    ├─→ MasterConnectionStateListener.onUpdate(DISCONNECTED)
    │       └─→ registryClient.getStoppable().stop(...)
    │
    └─→ MasterHeartBeatTask.writeHeartBeat()
            └─→ 检测到故障转移节点
                    └─→ registryClient.getStoppable().stop(...)

停止执行：
registryClient.getStoppable().stop(cause)
    └─→ MasterServer.stop(cause)
            ├─→ MasterServer.close(cause)
            │       ├─→ 设置停止标志
            │       ├─→ 关闭所有组件
            │       └─→ MasterRegistryClient.close()
            │               └─→ deregister()
            └─→ System.exit(1)
```

### 7.4 设计优势

1. **解耦**：`RegistryClient` 不直接依赖 `MasterServer`，通过接口回调，降低耦合度
2. **主动停止**：当检测到连接断开或故障转移时，主动停止服务，避免服务异常运行
3. **统一停止入口**：所有异常场景都通过 `IStoppable.stop()` 统一处理
4. **可扩展**：其他组件也可以实现 `IStoppable` 并注册

### 7.5 为什么需要这个机制？

#### 7.5.1 问题1：注册中心连接断开
- **问题**：无法更新心跳，其他节点可能认为该 Master 已下线
- **问题**：无法接收集群事件，状态可能不一致
- **解决**：检测到断开时主动停止，避免状态不一致

#### 7.5.2 问题2：故障转移检测
- **问题**：其他 Master 已经将该节点标记为故障转移
- **问题**：继续运行可能导致数据不一致
- **解决**：检测到故障转移节点时主动停止

#### 7.5.3 问题3：优雅关闭
- **问题**：需要统一入口执行清理工作
- **解决**：通过 `IStoppable.stop()` 统一处理，确保资源正确释放

### 7.6 时序图

```
MasterServer                MasterRegistryClient          RegistryClient          ConnectionListener
     |                              |                           |                          |
     |--start()-------------------->|                           |                          |
     |                              |--start()------------------>|                          |
     |                              |                           |--addConnectionStateListener->|
     |                              |                           |                          |
     |--setRegistryStoppable(this)->|                           |                          |
     |                              |--setStoppable(this)------->|                          |
     |                              |                           |                          |
     |                    [运行时：注册中心连接断开]              |                          |
     |                              |                           |                          |
     |                              |                           |--onUpdate(DISCONNECTED)->|
     |                              |                           |                          |
     |                              |                           |<--getStoppable()---------|
     |                              |                           |                          |
     |<--stop("disconnected")------|                           |                          |
     |                              |                           |                          |
     |--close()-------------------->|                           |                          |
     |                              |--close()------------------>|                          |
     |                              |                           |                          |
     |--System.exit(1)--------------|                           |                          |
```

---

## 8. 总结

### 8.1 核心机制

Master 服务注册机制的核心包括：

1. **心跳任务**：使用模板方法模式，定期更新服务状态
2. **服务注册**：负载检查 → 注册节点 → 验证成功 → 启动心跳
3. **服务注销**：删除节点 → 停止心跳 → 关闭连接
4. **异常处理**：通过回调机制，在异常情况下主动停止服务

### 8.2 设计特点

1. **分层设计**：应用层 → 门面层 → SPI 层 → 实现层
2. **插件化**：通过 SPI 机制支持多种注册中心
3. **统一 API**：`RegistryClient` 屏蔽底层差异
4. **Spring 集成**：通过自动配置简化使用

### 8.3 关键设计决策

1. **手动创建心跳任务**：精确控制生命周期，而非 Spring 管理
2. **临时节点**：使用 `persistEphemeral` 实现自动故障检测
3. **回调机制**：通过 `IStoppable` 实现异常情况下的主动停止
4. **负载保护**：注册前检查负载，避免高负载时注册

### 8.4 最佳实践

1. **注册前检查**：确保服务状态正常后再注册
2. **验证注册**：注册后验证节点是否成功创建
3. **优雅关闭**：关闭时先停止心跳，再删除节点，最后关闭连接
4. **异常处理**：捕获异常但不中断关闭流程，记录日志便于排查

### 8.5 注意事项

1. **心跳间隔**：根据集群规模调整心跳间隔，平衡性能和实时性
2. **超时设置**：注册验证的超时时间要合理，避免过长等待
3. **连接监听**：及时响应连接状态变化，避免服务异常运行
4. **故障转移**：检测到故障转移节点时立即停止，避免数据不一致

### 8.6 相关配置

```yaml
# application.yaml

# Master 配置
master:
  listen-port: 5678
  max-heartbeat-interval: 10s  # 心跳间隔
  server-load-protection:
    # 负载保护配置
    enabled: true
    max-cpu-usage: 80
    max-memory-usage: 80

# 注册中心配置
registry:
  type: zookeeper  # 可选: zookeeper, jdbc, etcd
  zookeeper:
    namespace: dolphinscheduler
    connect-string: localhost:2181
    session-timeout: 30s
```

---

## 9. 参考资料

- `MasterServer.java` - Master 服务器主类
- `MasterRegistryClient.java` - Master 注册客户端
- `MasterHeartBeatTask.java` - Master 心跳任务
- `RegistryClient.java` - 注册中心客户端门面类
- `Registry.java` - 注册中心 SPI 接口
- `IStoppable.java` - 可停止服务接口

---

*文档最后更新时间：2024年*