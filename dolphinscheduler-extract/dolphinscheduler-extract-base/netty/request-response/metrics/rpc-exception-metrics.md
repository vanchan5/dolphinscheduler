# RPC 监控指标记录机制

## 一、代码位置

### 1.1 异常指标记录

```java
// NettyRemotingClient.java (line 132-134)
ClientSyncExceptionMetrics clientSyncExceptionMetrics =
    ClientSyncExceptionMetrics.of(syncRequestDto, ex);
RpcMetrics.recordClientSyncRequestException(clientSyncExceptionMetrics);
```

### 1.2 请求耗时指标记录

```java
// NettyRemotingClient.java (line 155-158)
ClientSyncDurationMetrics clientSyncDurationMetrics = ClientSyncDurationMetrics
    .of(syncRequestDto)
    .withMilliseconds(System.currentTimeMillis() - start);
RpcMetrics.recordClientSyncRequestDuration(clientSyncDurationMetrics);
```

## 二、作用说明

### 2.1 异常指标记录

这两行代码用于**记录 RPC 调用异常的监控指标**，当 RPC 调用发生异常时，将异常信息记录到监控系统（Micrometer）中，用于后续的监控、告警和问题分析。

### 2.2 请求耗时指标记录

除了记录异常，系统还会**记录每次 RPC 调用的耗时**（无论成功还是失败），用于性能监控和分析。请求耗时指标在 `finally` 块中记录，确保无论请求成功或失败都会被记录。

## 三、详细机制

### 3.1 异常指标记录流程

```
RPC 调用发生异常
    ↓
创建 ClientSyncExceptionMetrics 对象
    ↓
提取异常信息（方法名、客户端主机、服务端主机、异常类型）
    ↓
记录到 Micrometer 全局注册表
    ↓
创建或更新 Counter 指标
    ↓
指标计数 +1
```

### 3.2 请求耗时指标记录流程

```
RPC 调用开始
    ↓
记录开始时间 start = System.currentTimeMillis()
    ↓
执行 RPC 调用（成功或失败）
    ↓
finally 块执行
    ↓
计算耗时：milliseconds = System.currentTimeMillis() - start
    ↓
创建 ClientSyncDurationMetrics 对象
    ↓
记录到 Micrometer 全局注册表
    ↓
创建或更新 Timer 指标
    ↓
记录耗时（包含百分位数统计）
```

### 3.3 ClientSyncExceptionMetrics 对象

`ClientSyncExceptionMetrics` 封装了异常相关的信息：

```java
public class ClientSyncExceptionMetrics {
    private Transporter transporter;      // 包含方法标识符
    private String clientHost;             // 客户端主机地址
    private String serverAddress;          // 服务端主机地址
    private Throwable throwable;           // 异常对象
}
```

**创建方式**：
```java
ClientSyncExceptionMetrics.of(syncRequestDto, ex)
```

从 `syncRequestDto` 中提取：
- `transporter`：包含方法标识符（methodIdentifier）
- `serverAddress`：服务端地址
- `clientHost`：客户端主机（自动获取）

### 3.4 ClientSyncDurationMetrics 对象

`ClientSyncDurationMetrics` 封装了请求耗时相关的信息：

```java
public class ClientSyncDurationMetrics {
    private Transporter transporter;      // 包含方法标识符
    private long milliseconds;             // 请求耗时（毫秒）
    private String clientHost;             // 客户端主机地址
    private String serverHost;             // 服务端主机地址
}
```

**创建方式**：
```java
ClientSyncDurationMetrics clientSyncDurationMetrics = ClientSyncDurationMetrics
    .of(syncRequestDto)
    .withMilliseconds(System.currentTimeMillis() - start);
```

从 `syncRequestDto` 中提取：
- `transporter`：包含方法标识符（methodIdentifier）
- `serverHost`：服务端主机地址
- `clientHost`：客户端主机（自动获取）
- `milliseconds`：请求耗时（通过 `withMilliseconds()` 设置）

### 3.5 异常指标记录过程

```java
// RpcMetrics.recordClientSyncRequestException()
public static void recordClientSyncRequestException(ClientSyncExceptionMetrics metrics) {
    // 1. 提取方法名
    String methodName = metrics.getTransporter()
        .getHeader()
        .getMethodIdentifier();
    
    // 2. 提取异常类型
    String exceptionType = metrics.getThrowable()
        .getClass()
        .getSimpleName();
    
    // 3. 创建或获取 Counter 指标
    Counter counter = Counter.builder("ds.rpc.client.sync.request.exception.count")
        .tag("method_name", methodName)        // RPC 方法名
        .tag("client_host", clientHost)         // 客户端主机
        .tag("server_host", serverHost)         // 服务端主机
        .tag("exception_name", exceptionType)  // 异常类型
        .register(Metrics.globalRegistry);
    
    // 4. 计数 +1
    counter.increment();
}
```

### 3.6 请求耗时指标记录过程

```java
// RpcMetrics.recordClientSyncRequestDuration()
public static void recordClientSyncRequestDuration(ClientSyncDurationMetrics metrics) {
    // 1. 提取方法名
    String methodName = metrics.getTransporter()
        .getHeader()
        .getMethodIdentifier();
    
    // 2. 创建或获取 Timer 指标
    Timer timer = Timer.builder("ds.rpc.client.sync.request.duration.time")
        .tag("method_name", methodName)        // RPC 方法名
        .tag("client_host", clientHost)         // 客户端主机
        .tag("server_host", serverHost)         // 服务端主机
        .publishPercentiles(0.5, 0.75, 0.95, 0.99)  // 发布百分位数
        .publishPercentileHistogram()              // 发布百分位直方图
        .register(Metrics.globalRegistry);
    
    // 3. 记录耗时（包含统计信息）
    timer.record(milliseconds, TimeUnit.MILLISECONDS);
}
```

**关键特性**：
- **Timer 类型**：记录耗时，自动计算平均值、最大值、百分位数等统计信息
- **百分位数**：发布 P50、P75、P95、P99 百分位数，用于性能分析
- **百分位直方图**：发布完整的百分位直方图，支持更详细的性能分析

## 四、记录的指标信息

### 4.1 异常指标

#### 4.1.1 指标名称

```
ds.rpc.client.sync.request.exception.count
```

#### 4.1.2 指标类型

**Counter（计数器）**：只能递增，用于统计异常发生的次数。

#### 4.1.3 指标标签（Tags）

| 标签名 | 说明 | 示例值 |
|--------|------|--------|
| `method_name` | RPC 方法名 | `dispatchTask` |
| `client_host` | 客户端主机地址 | `192.168.1.100:5678` |
| `server_host` | 服务端主机地址 | `192.168.1.200:1235` |
| `exception_name` | 异常类型 | `RemoteTimeoutException` |

#### 4.1.4 指标示例

```
ds_rpc_client_sync_request_exception_count_total{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235",
    exception_name="RemoteTimeoutException"
} 10.0
```

### 4.2 请求耗时指标

#### 4.2.1 指标名称

```
ds.rpc.client.sync.request.duration.time
```

#### 4.2.2 指标类型

**Timer（计时器）**：记录请求耗时，自动计算统计信息（平均值、最大值、百分位数等）。

#### 4.2.3 指标标签（Tags）

| 标签名 | 说明 | 示例值 |
|--------|------|--------|
| `method_name` | RPC 方法名 | `dispatchTask` |
| `client_host` | 客户端主机地址 | `192.168.1.100:5678` |
| `server_host` | 服务端主机地址 | `192.168.1.200:1235` |

#### 4.2.4 指标统计信息

Timer 指标会自动生成多个统计指标：

```
# 总请求数
ds_rpc_client_sync_request_duration_time_count{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235"
} 1000.0

# 总耗时（秒）
ds_rpc_client_sync_request_duration_time_sum{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235"
} 5000.0

# 平均值（秒）
ds_rpc_client_sync_request_duration_time_avg = 
    ds_rpc_client_sync_request_duration_time_sum / 
    ds_rpc_client_sync_request_duration_time_count

# P50 百分位数（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.5"} 4.5

# P75 百分位数（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.75"} 5.2

# P95 百分位数（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.95"} 6.8

# P99 百分位数（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.99"} 8.5

# 最大值（秒）
ds_rpc_client_sync_request_duration_time_max 10.2
```

#### 4.2.5 指标示例

```
# 请求总数
ds_rpc_client_sync_request_duration_time_count{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235"
} 1000.0

# 总耗时（秒）
ds_rpc_client_sync_request_duration_time_sum{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235"
} 5000.0

# P95 百分位数（秒）
ds_rpc_client_sync_request_duration_time{
    method_name="dispatchTask",
    client_host="192.168.1.100:5678",
    server_host="192.168.1.200:1235",
    quantile="0.95"
} 6.8
```

## 五、使用场景

### 5.1 监控异常趋势

通过 Prometheus 查询异常趋势：

```promql
# 查询所有 RPC 异常总数
sum(ds_rpc_client_sync_request_exception_count_total)

# 查询特定方法的异常数
sum(ds_rpc_client_sync_request_exception_count_total{method_name="dispatchTask"})

# 查询特定异常类型的数量
sum(ds_rpc_client_sync_request_exception_count_total{exception_name="RemoteTimeoutException"})
```

### 5.2 监控请求耗时

通过 Prometheus 查询请求耗时：

```promql
# 查询平均响应时间（秒）
rate(ds_rpc_client_sync_request_duration_time_sum[5m]) / 
rate(ds_rpc_client_sync_request_duration_time_count[5m])

# 查询 P95 响应时间（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.95"}

# 查询 P99 响应时间（秒）
ds_rpc_client_sync_request_duration_time{quantile="0.99"}

# 查询特定方法的平均响应时间
rate(ds_rpc_client_sync_request_duration_time_sum{method_name="dispatchTask"}[5m]) / 
rate(ds_rpc_client_sync_request_duration_time_count{method_name="dispatchTask"}[5m])

# 查询请求速率（QPS）
rate(ds_rpc_client_sync_request_duration_time_count[5m])
```

### 5.3 定位问题方法

按方法名分组，找出异常最多的方法：

```promql
# 按方法名分组统计异常数
sum by (method_name) (ds_rpc_client_sync_request_exception_count_total)
```

**结果示例**：
```
method_name="dispatchTask"    100
method_name="killTask"         2
method_name="pauseTask"        1
```

按方法名分组，找出响应时间最慢的方法：

```promql
# 按方法名分组统计平均响应时间
avg by (method_name) (
    rate(ds_rpc_client_sync_request_duration_time_sum[5m]) / 
    rate(ds_rpc_client_sync_request_duration_time_count[5m])
)
```

**结果示例**：
```
method_name="dispatchTask"    5.2秒
method_name="killTask"        0.5秒
method_name="pauseTask"       0.3秒
```

### 5.4 定位问题主机

按服务端主机分组，找出异常最多的服务器：

```promql
# 按服务端主机分组统计异常数
sum by (server_host) (ds_rpc_client_sync_request_exception_count_total)
```

**结果示例**：
```
server_host="192.168.1.200:1235"    50
server_host="192.168.1.201:1235"    2
```

按服务端主机分组，找出响应时间最慢的服务器：

```promql
# 按服务端主机分组统计平均响应时间
avg by (server_host) (
    rate(ds_rpc_client_sync_request_duration_time_sum[5m]) / 
    rate(ds_rpc_client_sync_request_duration_time_count[5m])
)
```

**结果示例**：
```
server_host="192.168.1.200:1235"    6.5秒
server_host="192.168.1.201:1235"    2.1秒
```

### 5.6 设置告警规则

在 Prometheus 中设置告警规则：

```yaml
groups:
  - name: rpc_exception_alerts
    rules:
      - alert: HighRpcExceptionRate
        expr: rate(ds_rpc_client_sync_request_exception_count_total[5m]) > 0.1
        for: 5m
        annotations:
          summary: "RPC 异常率过高"
          description: "RPC 调用异常率超过 0.1 次/秒，持续 5 分钟"
      
      - alert: HighRpcResponseTime
        expr: |
          rate(ds_rpc_client_sync_request_duration_time_sum[5m]) / 
          rate(ds_rpc_client_sync_request_duration_time_count[5m]) > 5
        for: 5m
        annotations:
          summary: "RPC 响应时间过长"
          description: "RPC 调用平均响应时间超过 5 秒，持续 5 分钟"
      
      - alert: HighRpcP95ResponseTime
        expr: ds_rpc_client_sync_request_duration_time{quantile="0.95"} > 10
        for: 5m
        annotations:
          summary: "RPC P95 响应时间过长"
          description: "RPC 调用 P95 响应时间超过 10 秒，持续 5 分钟"
```

## 六、如何查看指标

### 6.1 通过 Actuator 端点

```bash
# 查看所有指标
curl http://localhost:5679/actuator/metrics

# 查看特定指标
curl http://localhost:5679/actuator/metrics/ds.rpc.client.sync.request.exception.count

# 查看 Prometheus 格式的指标
curl http://localhost:5679/actuator/prometheus | grep rpc_client_sync_request_exception
```

### 6.2 通过 Prometheus

如果配置了 Prometheus：

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'dolphinscheduler-master'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:5679']
```

在 Prometheus UI 中查询：
```
http://localhost:9090/graph?g0.expr=ds_rpc_client_sync_request_exception_count_total
```

### 6.3 通过 Grafana

在 Grafana 中创建 Dashboard，可视化展示：

**异常指标**：
- 异常总数趋势图
- 按方法名分组的异常统计
- 按异常类型分组的异常统计
- 按主机分组的异常统计

**耗时指标**：
- 平均响应时间趋势图
- P50/P75/P95/P99 响应时间趋势图
- 按方法名分组的响应时间统计
- 按主机分组的响应时间统计
- QPS（每秒请求数）趋势图

## 七、数据持久化

### 7.1 内存存储

指标默认存储在 `Metrics.globalRegistry` 中（内存）：

```java
.register(Metrics.globalRegistry)  // 存储在内存中
```

**特点**：
- 服务重启后，内存中的数据会丢失
- 适合临时监控和调试

### 7.2 Prometheus 持久化

如果配置了 Prometheus：

```
服务运行：
  RPC 异常 → 记录到 Metrics.globalRegistry（内存）
  → 指标计数：10次异常

Prometheus 采集（每15秒）：
  访问 http://localhost:5679/actuator/prometheus
  → 采集指标数据
  → 存储到 Prometheus 时序数据库（持久化）✅

服务重启：
  Metrics.globalRegistry 清空（内存数据丢失）
  → 但 Prometheus 中已有历史数据 ✅
  → 可以查看重启前的指标数据
```

**建议**：
- 生产环境必须配置 Prometheus，确保指标持久化
- 通过 Grafana 可视化查看历史趋势
- 可以设置告警规则，基于历史数据判断异常

## 八、代码示例

### 8.1 完整代码示例

```java
// NettyRemotingClient.sendSync()
while (true) {
    final long start = System.currentTimeMillis();  // 记录开始时间
    try {
        return doSendSync(transporter, host, timeoutMillis);
    } catch (Exception ex) {
        // 1. 创建异常指标对象
        ClientSyncExceptionMetrics clientSyncExceptionMetrics =
            ClientSyncExceptionMetrics.of(syncRequestDto, ex);
        
        // 2. 记录异常指标
        RpcMetrics.recordClientSyncRequestException(clientSyncExceptionMetrics);
        
        // 3. 继续处理（重试或抛异常）
        if (currentExecuteTimes < maxRetryTimes
                && Arrays.stream(retryStrategy.retryFor())
                    .anyMatch(e -> e.isInstance(ex))) {
            currentExecuteTimes++;
            continue;  // 重试
        }
        throw new RemoteException("Call method to " + host + " failed", ex);
    } finally {
        // 4. 计算请求耗时
        long milliseconds = System.currentTimeMillis() - start;
        
        // 5. 创建耗时指标对象
        ClientSyncDurationMetrics clientSyncDurationMetrics = ClientSyncDurationMetrics
            .of(syncRequestDto)
            .withMilliseconds(milliseconds);
        
        // 6. 记录请求耗时指标（无论成功还是失败都会记录）
        RpcMetrics.recordClientSyncRequestDuration(clientSyncDurationMetrics);
    }
}
```

**关键点**：
- **异常指标**：在 `catch` 块中记录，只有发生异常时才记录
- **耗时指标**：在 `finally` 块中记录，无论成功还是失败都会记录
- **耗时计算**：使用 `System.currentTimeMillis()` 计算请求耗时

### 8.2 异常指标记录实现

```java
// RpcMetrics.recordClientSyncRequestException()
public static void recordClientSyncRequestException(
        ClientSyncExceptionMetrics clientSyncExceptionMetrics) {
    recordClientSyncRequestException(
        clientSyncExceptionMetrics.getThrowable(),
        Optional.of(clientSyncExceptionMetrics)
            .map(ClientSyncExceptionMetrics::getTransporter)
            .map(Transporter::getHeader)
            .map(TransporterHeader::getMethodIdentifier)
            .orElse("unknown"),
        clientSyncExceptionMetrics.getClientHost(),
        clientSyncExceptionMetrics.getServerAddress());
}

public static void recordClientSyncRequestException(
        final Throwable throwable,
        final String methodName,
        final String clientHost,
        final String serverHost) {
    final String exceptionType = throwable == null 
        ? "unknown" 
        : throwable.getClass().getSimpleName();
    
    final Counter counter = rpcRequestExceptionCounter.computeIfAbsent(
        exceptionType,
        (et) -> Counter.builder("ds.rpc.client.sync.request.exception.count")
            .tag("method_name", methodName)
            .tag("client_host", clientHost)
            .tag("server_host", serverHost)
            .tag("exception_name", et)
            .description("rpc sync request exception counter for exception type: " + et)
            .register(Metrics.globalRegistry));
    
    counter.increment();
}
```

### 8.3 请求耗时指标记录实现

```java
// RpcMetrics.recordClientSyncRequestDuration()
public static void recordClientSyncRequestDuration(
        ClientSyncDurationMetrics clientSyncDurationMetrics) {
    recordClientSyncRequestDuration(
        Optional.of(clientSyncDurationMetrics)
            .map(ClientSyncDurationMetrics::getTransporter)
            .map(Transporter::getHeader)
            .map(TransporterHeader::getMethodIdentifier)
            .orElseGet(() -> "unknown"),
        clientSyncDurationMetrics.getMilliseconds(),
        clientSyncDurationMetrics.getClientHost(),
        clientSyncDurationMetrics.getServerHost());
}

public static void recordClientSyncRequestDuration(
        final String methodName,
        final long milliseconds,
        final String clientHost,
        final String serverHost) {
    rpcRequestDurationTimer.computeIfAbsent(
        methodName,
        (method) -> Timer.builder("ds.rpc.client.sync.request.duration.time")
            .tag("method_name", method)
            .tag("client_host", clientHost)
            .tag("server_host", serverHost)
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)  // 发布百分位数
            .publishPercentileHistogram()              // 发布百分位直方图
            .description("time cost of sync rpc request, unit ms")
            .register(Metrics.globalRegistry))
        .record(milliseconds, TimeUnit.MILLISECONDS);
}
```

## 九、总结

### 9.1 核心作用

#### 9.1.1 异常指标

1. **记录异常**：每次 RPC 调用异常时，自动记录到监控系统
2. **提供维度**：记录方法名、客户端主机、服务端主机、异常类型等多维度信息
3. **支持监控**：可通过 Prometheus、Grafana 等工具查看和分析
4. **支持告警**：可基于异常指标设置告警规则

#### 9.1.2 请求耗时指标

1. **记录耗时**：每次 RPC 调用（无论成功还是失败）都会记录耗时
2. **性能分析**：提供平均值、最大值、百分位数等统计信息
3. **性能监控**：可监控响应时间趋势，发现性能问题
4. **性能告警**：可基于响应时间设置告警规则

### 9.2 关键点

#### 9.2.1 异常指标

- **指标类型**：Counter（计数器），只能递增
- **存储位置**：`Metrics.globalRegistry`（内存）
- **持久化**：需要配置 Prometheus 才能持久化历史数据
- **指标名称**：`ds.rpc.client.sync.request.exception.count`
- **标签维度**：method_name、client_host、server_host、exception_name
- **记录时机**：仅在发生异常时记录（`catch` 块中）

#### 9.2.2 请求耗时指标

- **指标类型**：Timer（计时器），记录耗时并自动计算统计信息
- **存储位置**：`Metrics.globalRegistry`（内存）
- **持久化**：需要配置 Prometheus 才能持久化历史数据
- **指标名称**：`ds.rpc.client.sync.request.duration.time`
- **标签维度**：method_name、client_host、server_host
- **记录时机**：无论成功还是失败都会记录（`finally` 块中）
- **统计信息**：自动计算平均值、最大值、P50/P75/P95/P99 百分位数

### 9.3 最佳实践

1. **生产环境配置 Prometheus**：确保指标持久化
2. **设置告警规则**：
   - 异常率告警：及时发现异常问题
   - 响应时间告警：及时发现性能问题
3. **定期分析指标**：
   - 找出异常最多的方法和主机
   - 找出响应时间最慢的方法和主机
4. **结合日志分析**：指标提供趋势，日志提供细节
5. **性能优化**：基于 P95/P99 响应时间定位性能瓶颈

## 十、相关文件

- **指标记录类**：`RpcMetrics.java`
- **异常指标对象**：`ClientSyncExceptionMetrics.java`
- **耗时指标对象**：`ClientSyncDurationMetrics.java`
- **RPC 客户端**：`NettyRemotingClient.java`
- **监控配置**：`application.yaml`（配置 `metrics.enabled: true`）

## 十一、指标对比

| 特性 | 异常指标 | 请求耗时指标 |
|------|---------|-------------|
| **指标名称** | `ds.rpc.client.sync.request.exception.count` | `ds.rpc.client.sync.request.duration.time` |
| **指标类型** | Counter（计数器） | Timer（计时器） |
| **记录时机** | 仅在发生异常时记录 | 无论成功还是失败都记录 |
| **记录位置** | `catch` 块 | `finally` 块 |
| **主要用途** | 异常监控和告警 | 性能监控和分析 |
| **统计信息** | 计数（总数） | 平均值、最大值、百分位数 |
| **标签维度** | method_name、client_host、server_host、exception_name | method_name、client_host、server_host |
| **告警场景** | 异常率过高 | 响应时间过长 |
