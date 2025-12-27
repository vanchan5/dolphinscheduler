# Master 心跳任务分析

## 一、核心组件

### 1. MasterRegistryClient (`@Component`)
- **职责**：管理 Master 服务的注册和心跳任务的生命周期
- **位置**：`org.apache.dolphinscheduler.server.master.registry.MasterRegistryClient`
- **关键方法**：
    - `start()`: 创建心跳任务并启动注册流程
    - `registry()`: 执行注册逻辑，包括负载检查和心跳线程启动
    - `close()`: 关闭心跳任务和注销服务

### 2. MasterHeartBeatTask (非 Spring Bean)
- **职责**：Master 服务的心跳任务实现
- **位置**：`org.apache.dolphinscheduler.server.master.registry.MasterHeartBeatTask`
- **继承关系**：`BaseHeartBeatTask<MasterHeartBeat>`
- **特点**：通过构造函数注入依赖，不使用 Spring 依赖注入

### 3. BaseHeartBeatTask (抽象基类)
- **职责**：心跳任务的抽象基类，使用模板方法模式
- **位置**：`org.apache.dolphinscheduler.common.model.BaseHeartBeatTask`
- **设计模式**：模板方法模式
- **模板方法**：`run()` 定义算法骨架
- **抽象方法**：`getHeartBeat()` 和 `writeHeartBeat()` 由子类实现

## 二、调用链路

```
MasterServer.initialized() [@PostConstruct]
  └─> MasterRegistryClient.start()
       ├─> new MasterHeartBeatTask(...)  // 创建心跳任务（未启动线程）
       └─> MasterRegistryClient.registry()
            ├─> masterHeartBeatTask.getHeartBeat()  // 获取心跳数据用于负载检查
            ├─> 负载保护检查（如果 BUSY 则等待）
            ├─> registryClient.persistEphemeral(...)  // 注册到注册中心
            ├─> 验证注册成功
            └─> masterHeartBeatTask.start()  // 启动心跳线程
                 └─> BaseHeartBeatTask.run()  // 心跳循环
                      ├─> getHeartBeat()  // 获取心跳数据
                      └─> writeHeartBeat()  // 写入注册中心
```

### 详细流程说明

1. **MasterServer 初始化** (`@PostConstruct`)
    - 调用 `masterRegistryClient.start()`

2. **MasterRegistryClient.start()**
    - 创建 `MasterHeartBeatTask` 实例（通过构造函数注入依赖）
    - 调用 `registry()` 方法执行注册

3. **MasterRegistryClient.registry()**
    - 调用 `masterHeartBeatTask.getHeartBeat()` 获取心跳数据
    - 检查服务负载状态，如果 `BUSY` 则等待
    - 使用心跳数据注册到注册中心
    - 验证注册是否成功
    - 启动心跳线程：`masterHeartBeatTask.start()`

4. **BaseHeartBeatTask.run()** (心跳循环)
    - 循环调用 `getHeartBeat()` 获取心跳数据
    - 根据条件调用 `writeHeartBeat()` 写入注册中心
    - 每次循环休眠 1 秒

## 三、设计模式：模板方法模式

### 模式结构

**BaseHeartBeatTask** 使用模板方法模式：

```java
// 模板方法：定义算法骨架
public void run() {
    while (runningFlag) {
        T heartBeat = getHeartBeat();  // 抽象方法：由子类实现
        if (需要写入) {
            writeHeartBeat(heartBeat);  // 抽象方法：由子类实现
        }
        Thread.sleep(1000);
    }
}

// 抽象方法：由子类实现
public abstract T getHeartBeat();
public abstract void writeHeartBeat(T heartBeat);
```

### 子类实现

**MasterHeartBeatTask** 实现抽象方法：

1. **getHeartBeat()**:
    - 收集系统指标（CPU、内存、磁盘）
    - 检查服务负载状态
    - 构建 `MasterHeartBeat` 对象

2. **writeHeartBeat()**:
    - 检查故障转移节点
    - 写入注册中心
    - 更新监控指标

### 模式优势

- **代码复用**：心跳循环逻辑在基类中统一实现
- **扩展性**：不同服务（Master、Worker、Alert）只需实现抽象方法
- **维护性**：修改心跳逻辑只需修改基类

## 四、心跳数据内容

### MasterHeartBeat 数据结构

`MasterHeartBeat` 继承自 `BaseHeartBeat`，包含以下数据：

#### 系统指标
- `cpuUsage`: 系统 CPU 使用率（百分比）
- `memoryUsage`: 系统内存使用率（百分比）
- `diskUsage`: 磁盘使用率（百分比）

#### JVM 指标
- `jvmCpuUsage`: JVM CPU 使用率（百分比）
- `jvmMemoryUsage`: JVM 内存使用率（百分比）

#### 服务状态
- `serverStatus`: 服务状态枚举
    - `NORMAL`: 正常状态
    - `BUSY`: 负载过高（由负载保护机制决定）
- `isCoordinator`: 是否为协调者（MasterCoordinator.isActive()）

#### 其他信息
- `startupTime`: 服务启动时间（毫秒时间戳）
- `reportTime`: 心跳报告时间（毫秒时间戳）
- `processId`: 进程 ID
- `host`: 主机地址
- `port`: 监听端口

### 数据收集流程

```java
@Override
public MasterHeartBeat getHeartBeat() {
    // 1. 获取系统指标
    SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
    
    // 2. 检查服务负载状态
    ServerStatus serverStatus = getServerStatus(systemMetrics, masterConfig.getServerLoadProtection());
    
    // 3. 构建心跳数据
    return MasterHeartBeat.builder()
            .startupTime(ServerLifeCycleManager.getServerStartupTime())
            .reportTime(System.currentTimeMillis())
            .jvmCpuUsage(systemMetrics.getJvmCpuUsagePercentage())
            .cpuUsage(systemMetrics.getSystemCpuUsagePercentage())
            .jvmMemoryUsage(systemMetrics.getJvmMemoryUsedPercentage())
            .memoryUsage(systemMetrics.getSystemMemoryUsedPercentage())
            .diskUsage(systemMetrics.getDiskUsedPercentage())
            .processId(processId)
            .serverStatus(serverStatus)
            .host(NetUtils.getHost())
            .port(masterConfig.getListenPort())
            .isCoordinator(masterCoordinator.isActive())
            .build();
}
```

## 五、写入逻辑

### 写入时机

心跳数据写入注册中心的时机由 `BaseHeartBeatTask.run()` 控制：

1. **定期写入**：距离上次写入时间 >= `heartBeatInterval`（默认 10 秒）
   ```java
   if (System.currentTimeMillis() - lastWriteTime >= heartBeatInterval) {
       writeHeartBeat(heartBeat);
   }
   ```

2. **状态变化时立即写入**：`serverStatus` 发生变化时立即写入
   ```java
   if (!lastHeartBeat.getServerStatus().equals(heartBeat.getServerStatus())) {
       writeHeartBeat(heartBeat);
   }
   ```

### 写入流程

`MasterHeartBeatTask.writeHeartBeat()` 的执行流程：

```java
@Override
public void writeHeartBeat(final MasterHeartBeat masterHeartBeat) {
    // 1. 检查故障转移节点
    final String failoverNodePath = RegistryUtils.getFailoveredNodePath(masterHeartBeat);
    if (registryClient.exists(failoverNodePath)) {
        log.warn("The master: {} is under {}, means it has been failover will close myself",
                masterHeartBeat, failoverNodePath);
        registryClient.getStoppable().stop("The master exist: " + failoverNodePath);
        return;
    }
    
    // 2. 写入注册中心
    String masterHeartBeatJson = JSONUtils.toJsonString(masterHeartBeat);
    registryClient.persistEphemeral(heartBeatPath, masterHeartBeatJson);
    
    // 3. 更新监控指标
    MasterServerMetrics.incMasterHeartbeatCount();
    
    log.debug("Success write master heartBeatInfo into registry, masterRegistryPath: {}, heartBeatInfo: {}",
            heartBeatPath, masterHeartBeatJson);
}
```

### 关键点

1. **故障转移检查**：如果检测到故障转移节点，立即停止服务
2. **Ephemeral 节点**：使用 `persistEphemeral` 创建临时节点，服务断开时自动删除
3. **JSON 序列化**：心跳数据以 JSON 格式存储
4. **监控指标**：每次写入都会更新心跳计数指标

## 六、服务负载保护

### 负载检查机制

服务负载状态由 `MasterHeartBeatTask.getServerStatus()` 决定：

```java
private ServerStatus getServerStatus(final SystemMetrics systemMetrics,
                                     final MasterServerLoadProtection masterServerLoadProtection) {
    return masterServerLoadProtection.isOverload(systemMetrics) 
        ? ServerStatus.BUSY 
        : ServerStatus.NORMAL;
}
```

### 负载阈值

`BaseServerLoadProtection.isOverload()` 检查以下阈值（默认均为 70%）：

- `maxSystemCpuUsagePercentageThresholds`: 0.7 (70%)
- `maxJvmCpuUsagePercentageThresholds`: 0.7 (70%)
- `maxSystemMemoryUsagePercentageThresholds`: 0.7 (70%)
- `maxDiskUsagePercentageThresholds`: 0.7 (70%)

**检查逻辑**：只要有一个指标超过阈值，就返回 `true`（负载过高）

```java
@Override
public boolean isOverload(SystemMetrics systemMetrics) {
    if (!enabled) {
        return false;
    }
    if (systemMetrics.getSystemCpuUsagePercentage() > maxSystemCpuUsagePercentageThresholds) {
        return true;
    }
    if (systemMetrics.getJvmCpuUsagePercentage() > maxJvmCpuUsagePercentageThresholds) {
        return true;
    }
    if (systemMetrics.getDiskUsedPercentage() > maxDiskUsagePercentageThresholds) {
        return true;
    }
    if (systemMetrics.getSystemMemoryUsedPercentage() > maxSystemMemoryUsagePercentageThresholds) {
        return true;
    }
    return false;
}
```

### 注册前负载保护

在 `MasterRegistryClient.registry()` 中，注册前会检查服务负载：

```java
// 获取心跳数据(业务服务负载情况数据)
MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();

// 负载保护：如果负载过高，则每隔1S获取负载数据，直到服务达标
while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
    log.warn("Master node is BUSY: {}", heartBeat);
    heartBeat = masterHeartBeatTask.getHeartBeat();
    ThreadUtils.sleep(SLEEP_TIME_MILLIS);  // 等待 1 秒后重试
}

// 只有在负载正常时才注册
registryClient.persistEphemeral(masterRegistryPath, JSONUtils.toJsonString(heartBeat));
```

**作用**：确保服务在负载正常时才注册到注册中心，避免高负载时接受新任务。

## 七、为什么 MasterHeartBeatTask 不使用 Spring 依赖注入？

### 问题背景

在 `MasterRegistryClient.start()` 中，代码通过构造函数手动创建 `MasterHeartBeatTask`：

```java
this.masterHeartBeatTask = new MasterHeartBeatTask(
    masterConfig, 
    metricsProvider, 
    registryClient, 
    masterCoordinator
);
```

而不是使用 `@Autowired` 注入。为什么？

### 原因分析

#### 1. 生命周期需要精确控制

心跳任务的创建和启动时机需要精确控制：

- **创建时机**：在 `MasterRegistryClient.start()` 中按需创建
- **启动时机**：注册成功并验证后启动（`registry()` 方法末尾）
- **关闭时机**：在 `close()` 中调用 `shutdown()`

如果使用 Spring 注入，Bean 会在容器初始化时创建，但此时：
- 注册流程尚未开始
- 无法在注册前获取心跳数据
- 线程启动时机难以控制

#### 2. 与注册流程紧密耦合

心跳任务的创建和启动是注册流程的一部分，需要在 `start()` 中按顺序执行：

```
创建心跳任务 
  → 获取心跳数据检查负载 
  → 使用心跳数据注册到注册中心 
  → 验证注册成功 
  → 启动心跳线程
```

如果使用 Spring 注入：
- Bean 会在容器初始化时创建，时机不对
- 线程启动时机难以精确控制
- 关闭需要配合 Spring 的销毁流程

#### 3. 需要在注册前使用心跳数据

在 `MasterRegistryClient.registry()` 中，注册前需要调用 `getHeartBeat()`：

```java
// 获取心跳数据用于负载检查
MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();

// 负载保护检查
while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
    heartBeat = masterHeartBeatTask.getHeartBeat();
    ThreadUtils.sleep(SLEEP_TIME_MILLIS);
}

// 使用心跳数据注册
registryClient.persistEphemeral(masterRegistryPath, JSONUtils.toJsonString(heartBeat));
```

如果使用 Spring 注入，Bean 创建时机可能早于注册流程，但此时可能无法获取有效的心跳数据。

#### 4. 延迟启动线程

心跳任务需要先创建（用于获取心跳数据），但线程需要延迟启动（注册成功后才启动）：

```java
// 1. 创建心跳任务（不启动线程）
this.masterHeartBeatTask = new MasterHeartBeatTask(...);

// 2. 注册流程中使用心跳数据
MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();
// ... 注册逻辑 ...

// 3. 注册成功后才启动心跳线程
masterHeartBeatTask.start();
```

Spring 管理的 Bean 难以实现这种"创建但不启动"的模式。

### 总结

`MasterHeartBeatTask` 不使用 Spring 依赖注入的核心原因：

1. **时机问题**：需要在注册流程中精确控制创建和启动时机
2. **耦合问题**：与注册流程紧密耦合，需要在注册前使用心跳数据
3. **延迟启动**：需要先创建对象，延迟启动线程

## 八、对比：SystemEventBusFireWorker 为什么可以用 Spring 注入？

### SystemEventBusFireWorker 的特点

```java
@Slf4j
@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class SystemEventBusFireWorker extends BaseDaemonThread implements AutoCloseable {

    @Autowired
    private SystemEventBus systemEventBus;

    @Autowired
    private FailoverCoordinator failoverCoordinator;

    @Autowired
    private List<ISystemEventHandler> systemEventHandlers;

    public SystemEventBusFireWorker() {
        super("SystemEventBusFireWorker");
    }

    @Override
    public void start() {
        flag = true;
        super.start();
        log.info("SystemEventBusFireWorker started");
    }
}
```

### 为什么可以用 Spring 注入？

#### 1. 启动时机不依赖注册流程

在 `MasterServer.initialized()` 中的启动顺序：

```java
this.masterRegistryClient.start();  // 先启动注册
// ... 其他组件启动
this.systemEventBusFireWorker.start();  // 最后启动事件总线
```

- `SystemEventBusFireWorker` 可以在 Spring 容器初始化后启动
- 不依赖注册流程的完成
- 启动时机相对灵活

#### 2. 不需要在注册前使用

- `SystemEventBusFireWorker` 只负责处理系统事件
- 不需要在注册前获取数据或执行逻辑
- 可以在任何时候启动，只要依赖的 Bean 已准备好

#### 3. 简单的生命周期

- **创建**：Spring 容器初始化时创建（`@Component`）
- **启动**：调用 `start()` 方法启动线程
- **关闭**：实现 `AutoCloseable`，在 `close()` 中停止

生命周期简单，不需要精确控制创建和启动时机。

#### 4. 依赖注入的组件都是 Spring Bean

```java
@Autowired
private SystemEventBus systemEventBus;  // Spring Bean

@Autowired
private FailoverCoordinator failoverCoordinator;  // Spring Bean

@Autowired
private List<ISystemEventHandler> systemEventHandlers;  // Spring Bean 列表
```

这些依赖在容器初始化时都已准备好，可以正常注入。

### 对比总结

| 特性 | MasterHeartBeatTask | SystemEventBusFireWorker |
|------|---------------------|--------------------------|
| **启动时机** | 必须在注册流程中精确控制 | 可以在容器初始化后启动 |
| **注册前使用** | 需要在注册前获取心跳数据 | 不需要 |
| **生命周期** | 创建→延迟启动→关闭 | 创建→启动→关闭 |
| **依赖关系** | 与注册流程紧密耦合 | 独立运行 |
| **Spring 注入** | ❌ 不适合（时机不对） | ✅ 适合（时机正确） |

### 关键区别

1. **MasterHeartBeatTask**：
    - 需要在注册前获取心跳数据
    - 需要延迟启动线程
    - 与注册流程紧密耦合
    - **不适合 Spring 注入**

2. **SystemEventBusFireWorker**：
    - 不需要在注册前使用
    - 可以在容器初始化后启动
    - 独立运行，不依赖注册流程
    - **适合 Spring 注入**

## 九、内存和 CPU 优化

### 为什么不会导致内存溢出和 CPU 飙高？

#### CPU 方面

```java
while (runningFlag) {
    try {
        T heartBeat = getHeartBeat();
        // ... 写入逻辑 ...
    } catch (Exception ex) {
        log.error("{} task execute failed", threadName, ex);
    } finally {
        Thread.sleep(DEFAULT_HEARTBEAT_SCAN_INTERVAL);  // 休眠 1 秒
    }
}
```

- `Thread.sleep(1000)` 让线程每次循环休眠 1 秒，避免 while 循环空转占用 CPU
- 如果没有 sleep，while 循环会空转，导致 CPU 使用率接近 100%（单核）

#### 内存方面

- 每次循环都会调用 `getHeartBeat()` 创建新的 `heartBeat` 对象（局部变量）
- 如果满足写入条件：`heartBeat` 赋值给 `lastHeartBeat`（成员变量），替换旧引用
- 如果不满足条件：`heartBeat` 是局部变量，循环结束后变成垃圾对象，可被 GC 回收
- 最多只有一个 `heartBeat` 对象存活（`lastHeartBeat` 指向的），不会有对象积累
- `Thread.sleep` 降低了对象创建频率，给 GC 充分的回收时间

### 线程退出机制

```java
public void shutdown() {
    runningFlag = false;
    log.warn("{} finished...", threadName);
}
```

**退出流程**：
1. 调用 `shutdown()` 方法，将 `runningFlag` 设置为 `false`
2. `while (runningFlag)` 循环条件不满足，循环退出
3. `run()` 方法执行完毕，线程自然结束
4. 在 `MasterRegistryClient.close()` 中调用 `shutdown()`

**优雅退出**：
- 通过标志位 `runningFlag` 控制线程退出
- 线程在循环中检查标志位，不会强制中断
- 确保当前循环执行完毕后再退出

## 十、注册中心路径和故障转移

### 注册中心路径

Master 服务在注册中心的路径格式：

```
/dolphinscheduler/nodes/master/ip:port
```

**路径组成**：
- 命名空间：`/dolphinscheduler`
- 节点类型：`/nodes/master`
- 节点标识：`ip:port`（如 `192.168.1.100:5678`）

**配置来源**：
```java
String masterRegistryPath = masterConfig.getMasterRegistryPath();
// 格式：/dolphinscheduler/nodes/master/ip:port
```

### 故障转移机制

#### 1. 故障转移节点检查

在 `writeHeartBeat()` 中，每次写入前都会检查故障转移节点：

```java
final String failoverNodePath = RegistryUtils.getFailoveredNodePath(masterHeartBeat);
if (registryClient.exists(failoverNodePath)) {
    log.warn("The master: {} is under {}, means it has been failover will close myself",
            masterHeartBeat, failoverNodePath);
    registryClient.getStoppable().stop("The master exist: " + failoverNodePath);
    return;
}
```

**作用**：
- 如果检测到当前 Master 节点在故障转移列表中，说明该节点已被标记为故障
- 立即停止服务，避免继续提供服务

#### 2. 注册验证机制

在 `registry()` 方法中，注册后会验证节点是否存在：

```java
int checkCount = 0;
while (!registryClient.checkNodeExists(NetUtils.getHost(), RegistryNodeType.MASTER)) {
    checkCount++;
    log.warn("The current master server node:{} cannot find in registry, check count: {}, registry path: {}", 
            NetUtils.getHost(), checkCount, masterRegistryPath);
    ThreadUtils.sleep(SLEEP_TIME_MILLIS);
    // 超时保护：避免无限循环
    if (checkCount > 30) {
        throw new RegistryException("Master node cannot be found in registry after multiple checks");
    }
}
```

**作用**：
- 确保注册成功，节点在注册中心可见
- 防止注册中心同步延迟导致的问题
- 超时保护：最多检查 30 次（约 30 秒）

#### 3. 故障转移等待

注册成功后，等待 1 秒，确保故障转移节点已清理：

```java
// sleep 1s, waiting master failover remove
ThreadUtils.sleep(SLEEP_TIME_MILLIS);
```

**作用**：
- 给故障转移机制时间清理旧节点
- 避免新节点启动时与故障转移节点冲突

### Ephemeral 节点特性

使用 `persistEphemeral()` 创建临时节点：

```java
registryClient.persistEphemeral(masterRegistryPath, JSONUtils.toJsonString(heartBeat));
```

**特性**：
- **临时性**：服务断开连接时，节点自动删除
- **会话绑定**：节点与注册中心会话绑定
- **自动清理**：服务异常退出时，注册中心自动清理节点

## 十一、配置说明

### 心跳间隔配置

**配置项**：`master.max-heartbeat-interval`

**默认值**：`10s`（10 秒）

**配置位置**：`MasterConfig`

```java
private Duration maxHeartbeatInterval = Duration.ofSeconds(10);
```

**作用**：
- 控制心跳数据写入注册中心的频率
- 默认每 10 秒写入一次
- 状态变化时立即写入（不等待间隔）

### 负载保护配置

**配置项**：`master.server-load-protection.*`

**默认阈值**（均为 70%）：
- `maxSystemCpuUsagePercentageThresholds`: 0.7
- `maxJvmCpuUsagePercentageThresholds`: 0.7
- `maxSystemMemoryUsagePercentageThresholds`: 0.7
- `maxDiskUsagePercentageThresholds`: 0.7

**启用/禁用**：
- `enabled`: `true`（默认启用）

**配置示例**：
```yaml
master:
  server-load-protection:
    enabled: true
    max-system-cpu-usage-percentage-thresholds: 0.7
    max-jvm-cpu-usage-percentage-thresholds: 0.7
    max-system-memory-usage-percentage-thresholds: 0.7
    max-disk-usage-percentage-thresholds: 0.7
```

## 十二、异常处理

### 心跳循环异常处理

在 `BaseHeartBeatTask.run()` 中，使用 try-catch 包裹整个循环：

```java
while (runningFlag) {
    try {
        T heartBeat = getHeartBeat();
        // ... 写入逻辑 ...
    } catch (Exception ex) {
        log.error("{} task execute failed", threadName, ex);
    } finally {
        Thread.sleep(DEFAULT_HEARTBEAT_SCAN_INTERVAL);
    }
}
```

**异常处理策略**：
- **捕获所有异常**：确保心跳循环不会因异常而中断
- **记录日志**：记录异常信息，便于排查问题
- **继续运行**：异常后继续下一次循环，保证心跳持续

### 写入异常处理

在 `writeHeartBeat()` 中，如果写入失败：

```java
try {
    registryClient.persistEphemeral(heartBeatPath, masterHeartBeatJson);
} catch (Exception e) {
    log.error("Write heartbeat failed", e);
    // 异常会被 run() 方法捕获，不会中断心跳循环
}
```

**异常影响**：
- 单次写入失败不影响后续心跳
- 下次循环会继续尝试写入
- 如果连续失败，需要检查注册中心连接状态

### 注册异常处理

在 `MasterRegistryClient.registry()` 中：

```java
try {
    // 注册逻辑
} catch (Exception e) {
    throw new RegistryException("Master registry client start up error", e);
}
```

**异常处理**：
- 注册失败会抛出异常，阻止服务启动
- 确保服务在注册成功后才开始提供服务

## 十三、监控指标

### 心跳计数指标

**指标名称**：`ds.master.heartbeat.count`

**更新位置**：`MasterHeartBeatTask.writeHeartBeat()`

```java
MasterServerMetrics.incMasterHeartbeatCount();
```

**作用**：
- 统计心跳写入次数
- 用于监控心跳是否正常
- 可用于告警：如果心跳计数停止增长，说明心跳异常

### 系统指标监控

在 `MasterServer.initialized()` 中注册系统指标：

```java
MasterServerMetrics.registerMasterCpuUsageGauge(() -> {
    SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
    return systemMetrics.getSystemCpuUsagePercentage();
});

MasterServerMetrics.registerMasterMemoryAvailableGauge(() -> {
    SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
    return (systemMetrics.getSystemMemoryMax() - systemMetrics.getSystemMemoryUsed()) / 1024.0 / 1024 / 1024;
});

MasterServerMetrics.registerMasterMemoryUsageGauge(() -> {
    SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
    return systemMetrics.getJvmMemoryUsedPercentage();
});
```

**监控指标**：
- CPU 使用率
- 内存可用量（GB）
- 内存使用率

## 十四、与其他服务的对比

### Master vs Worker vs Alert

| 特性 | MasterHeartBeatTask | WorkerHeartBeatTask | AlertHeartbeatTask |
|------|---------------------|---------------------|-------------------|
| **Spring 注入** | ❌ 否 | ❌ 否 | ✅ 是 |
| **心跳数据** | `MasterHeartBeat` | `WorkerHeartBeat` | `AlertServerHeartBeat` |
| **特殊字段** | `isCoordinator` | `workerHostWeight`, `threadPoolUsage`, `workerGroup` | `isActive` |
| **负载保护** | ✅ 是 | ✅ 是 | ❌ 否（固定 NORMAL） |
| **故障转移检查** | ✅ 是 | ✅ 是 | ❌ 否 |
| **注册前使用** | ✅ 是（负载检查） | ✅ 是（负载检查） | ❌ 否 |

### 关键差异

#### 1. Spring 注入差异

- **Master/Worker**：不使用 Spring 注入，因为需要在注册前使用
- **Alert**：使用 Spring 注入（`@Component`），因为不需要在注册前使用

#### 2. 心跳数据差异

- **Master**：包含 `isCoordinator`（是否为协调者）
- **Worker**：包含 `workerHostWeight`（主机权重）、`threadPoolUsage`（线程池使用率）、`workerGroup`（工作组）
- **Alert**：包含 `isActive`（是否激活）

#### 3. 负载保护差异

- **Master/Worker**：根据系统指标动态判断 `NORMAL` 或 `BUSY`
- **Alert**：固定为 `NORMAL`，不进行负载检查

## 十五、最佳实践

### 1. 心跳间隔设置

**建议**：
- 生产环境：10-30 秒
- 测试环境：5-10 秒
- 不建议设置过小（< 5 秒），会增加注册中心压力

### 2. 负载阈值设置

**建议**：
- 根据实际服务器性能调整阈值
- 建议保留 20-30% 的余量，避免频繁触发负载保护
- 监控负载保护触发频率，调整阈值

### 3. 监控告警

**建议监控**：
- 心跳计数是否正常增长
- 心跳写入是否失败
- 服务状态是否频繁切换（NORMAL ↔ BUSY）
- 注册中心连接状态

### 4. 故障排查

**常见问题**：
1. **心跳写入失败**：检查注册中心连接状态
2. **服务状态频繁切换**：检查系统负载，调整负载阈值
3. **注册失败**：检查注册中心是否正常，网络是否通畅
4. **故障转移误触发**：检查故障转移节点路径是否正确

## 十六、总结

### 核心要点

1. **设计模式**：使用模板方法模式，统一心跳循环逻辑
2. **生命周期**：精确控制创建、启动、关闭时机
3. **负载保护**：注册前检查负载，确保服务稳定
4. **故障转移**：写入前检查故障转移节点，及时停止服务
5. **异常处理**：心跳循环异常不影响服务运行
6. **监控指标**：提供心跳计数和系统指标监控

### 关键设计决策

1. **不使用 Spring 注入**：需要在注册前使用，需要精确控制生命周期
2. **延迟启动线程**：先创建对象获取数据，注册成功后才启动线程
3. **定期 + 状态变化写入**：平衡性能和实时性
4. **Ephemeral 节点**：服务断开时自动清理，简化故障处理

### 扩展性

- **模板方法模式**：不同服务只需实现抽象方法
- **配置化**：心跳间隔、负载阈值可配置
- **监控友好**：提供丰富的监控指标

---

**相关文件**：
- `MasterRegistryClient.java`：注册和心跳任务管理
- `MasterHeartBeatTask.java`：心跳任务实现
- `BaseHeartBeatTask.java`：心跳任务基类
- `MasterConfig.java`：配置类
- `BaseServerLoadProtection.java`：负载保护基类
