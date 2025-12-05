# getConnection() 架构原理与灵活性详解

## 📋 概述

本文档详细解释 DolphinScheduler 如何通过 **三层架构**（SPI 契约层、API 实现层、插件适配层）实现 `getConnection()` 方法，展示系统的核心原理和灵活性设计。

---

## 🎯 核心问题

**如何优雅地获取不同数据库的连接？**

### 挑战
- ✅ 支持 **28 种**不同的数据源（MySQL、PostgreSQL、Oracle、Hive...）
- ✅ 每种数据源有不同的 **JDBC 驱动**、**URL 格式**、**验证查询**
- ✅ 需要支持**两种连接策略**（临时连接 vs 连接池）
- ✅ 新增数据源**不能修改核心代码**
- ✅ 连接获取方式需要**可定制、可扩展**

### 解决方案

通过 **三层架构 + SPI 机制 + 策略模式** 实现：

```
SPI 契约层 (定义 What)
    ↓
API 实现层 (实现 How)
    ↓
插件适配层 (适配 Adapt)
    ↓
java.sql.Connection
```

---

## 📊 架构图说明

### 文件 1: `spi-getConnection-principle.puml`

**完整架构图**，展示：
- ✅ 所有相关类和接口
- ✅ 两种连接策略的完整路径
- ✅ DataSourceProcessor 的核心作用
- ✅ 从业务调用到获取连接的完整流程
- ✅ MySQL 等具体实现示例

### 文件 2: `spi-getConnection-layered.puml`

**三层协作架构图**，聚焦：
- ✅ SPI、API、Plugin 三层的职责
- ✅ `getConnection()` 在各层的实现方式
- ✅ 设计原则和灵活性体现
- ✅ 简化的调用路径

---

## 🎯 四大管理器协作关系

### 管理器职责划分

```
DataSourcePluginManager (数据源插件管理器)
    ├─ 职责: 管理所有 DataSourceChannel
    ├─ 启动时: installPlugin()
    ├─ 使用: PrioritySPIFactory<DataSourceChannelFactory>
    └─ 维护: Map<String, DataSourceChannel>

DataSourceProcessorManager (处理器管理器)
    ├─ 职责: 管理所有 DataSourceProcessor
    ├─ 启动时: installProcessor()
    ├─ 使用: PrioritySPIFactory<DataSourceProcessor>
    └─ 维护: Map<String, DataSourceProcessor>

DataSourceClientProvider (客户端提供者 - 门面)
    ├─ 职责: 业务代码统一入口
    ├─ 依赖: DataSourcePluginManager
    ├─ 缓存: Guava Cache<String, PooledDataSourceClient>
    └─ 方法: getPooledConnection(), getAdHocConnection()

DataSourceProcessorProvider (处理器提供者)
    ├─ 职责: 提供 Processor 全局访问点
    ├─ 依赖: DataSourceProcessorManager
    └─ 方法: getDataSourceProcessor(DbType)
```

### 管理器协作流程

```
┌─────────────────────────────────────────────────────────┐
│ 系统启动阶段                                             │
├─────────────────────────────────────────────────────────┤
│                                                          │
│ 1. DataSourcePluginManager.installPlugin()              │
│    ↓                                                     │
│    new PrioritySPIFactory<>(DataSourceChannelFactory)   │
│    ↓ ServiceLoader 自动发现                             │
│    MySQLDataSourceChannelFactory                        │
│    PostgreSQLDataSourceChannelFactory                   │
│    ... 28 种                                            │
│    ↓ 遍历所有 Factory                                   │
│    factory.create() → DataSourceChannel                 │
│    ↓ 保存到 Map                                         │
│    datasourceChannelMap.put("mysql", MySQLChannel)      │
│                                                          │
│ 2. DataSourceProcessorManager.installProcessor()        │
│    ↓                                                     │
│    new PrioritySPIFactory<>(DataSourceProcessor)        │
│    ↓ ServiceLoader 自动发现                             │
│    MySQLDataSourceProcessor                             │
│    PostgreSQLDataSourceProcessor                        │
│    ... 28 种                                            │
│    ↓ 保存到 Map                                         │
│    dataSourceProcessorMap.put("MYSQL", MySQLProcessor)  │
│                                                          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 业务调用阶段                                             │
├─────────────────────────────────────────────────────────┤
│                                                          │
│ 业务代码                                                 │
│    ↓ 调用                                                │
│ DataSourceClientProvider.getPooledConnection(MYSQL, ...) │
│    ↓ 第1步: 获取 Channel                                │
│ DataSourcePluginManager.get("mysql")                    │
│    → 返回 MySQLDataSourceChannel                        │
│    ↓ 第2步: 创建 Client                                 │
│ MySQLChannel.createPooledDataSourceClient()             │
│    → 返回 MySQLPooledDataSourceClient                   │
│    ↓ 第3步: 初始化连接池                                │
│ new HikariDataSource() (内部需要 Processor)             │
│    ↓ 第4步: 获取 Processor                              │
│ DataSourceProcessorProvider.getDataSourceProcessor(MYSQL)│
│    ↓                                                     │
│ DataSourceProcessorManager.get("MYSQL")                 │
│    → 返回 MySQLDataSourceProcessor                      │
│    ↓ 第5步: 创建连接                                    │
│ MySQLProcessor.getConnection(params)                    │
│    → DriverManager.getConnection()                      │
│    → Connection                                         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### 管理器之间的依赖关系

```
DataSourceClientProvider (门面)
    │
    ├── 依赖 → DataSourcePluginManager
    │              ├── 使用 → PrioritySPIFactory
    │              │              └── 加载 → DataSourceChannelFactory
    │              └── 管理 → Map<String, DataSourceChannel>
    │
    └── 间接依赖 → DataSourceProcessorProvider
                      └── 依赖 → DataSourceProcessorManager
                                    ├── 使用 → PrioritySPIFactory
                                    │              └── 加载 → DataSourceProcessor
                                    └── 管理 → Map<String, DataSourceProcessor>
```

### 管理器调用链路

```
业务代码
    ↓
DataSourceClientProvider (门面 - 统一入口)
    ↓ 获取 Channel
DataSourcePluginManager (Channel 管理器)
    ↓ 返回
DataSourceChannel (通道)
    ↓ 创建
DataSourceClient (客户端)
    ↓ 需要 Processor
DataSourceProcessorProvider (Processor 提供者)
    ↓ 获取
DataSourceProcessorManager (Processor 管理器)
    ↓ 返回
DataSourceProcessor (处理器)
    ↓ 执行
getConnection() → Connection
```

---

## 🏗️ 三层架构详解

### 第一层：SPI 契约层 (dolphinscheduler-spi)

#### 职责：定义 What（做什么）

```java
// 定义客户端接口契约
public interface DataSourceClient extends AutoCloseable {
    Connection getConnection() throws SQLException;  // 核心方法
    void close();
}

// 两种客户端类型
public interface AdHocDataSourceClient extends DataSourceClient {
    // Marker Interface - 临时连接
}

public interface PooledDataSourceClient extends DataSourceClient {
    // 池化连接
    DataSource createDataSourcePool(params, dbType);
}

// SPI 扩展点
public interface DataSourceChannelFactory extends PrioritySPI {
    String getName();                  // 数据源名称
    DataSourceChannel create();        // 创建通道
}
```

#### 特点
- 🎯 **只定义接口**，不关心实现
- 🎯 **职责单一**：规定所有客户端必须有 `getConnection()` 方法
- 🎯 **开放扩展**：通过 `DataSourceChannelFactory` 支持插件
- 🎯 **不依赖具体**：不依赖任何数据库驱动

---

### 第二层：API 实现层 (dolphinscheduler-datasource-api)

#### 职责：实现 How（怎么做）

#### 2.1 策略选择器 - 门面

```java
public class DataSourceClientProvider {
    
    // 门面方法 1: 获取池化连接
    public static Connection getPooledConnection(
            DbType dbType, ConnectionParam params) {
        
        // 1. 检查缓存
        String key = getDatasourceUniqueId(params, dbType);
        DataSourceClient client = CACHE.get(key);
        
        if (client == null) {
            // 2. 创建新客户端
            DataSourceChannel channel = getChannel(dbType);
            client = channel.createPooledDataSourceClient(params, dbType);
            CACHE.put(key, client);
        }
        
        // 3. 获取连接
        return client.getConnection();
    }
    
    // 门面方法 2: 获取临时连接
    public static Connection getAdHocConnection(
            DbType dbType, ConnectionParam params) {
        
        // 1. 不使用缓存
        DataSourceChannel channel = getChannel(dbType);
        
        // 2. 创建临时客户端
        AdHocDataSourceClient client = 
            channel.createAdHocDataSourceClient(params, dbType);
        
        // 3. 获取连接
        return client.getConnection();
    }
}
```

#### 2.2 策略实现 - 两种路径

**路径 1: AdHoc 临时连接**

```java
public abstract class BaseAdHocDataSourceClient implements AdHocDataSourceClient {
    
    protected final BaseConnectionParam baseConnectionParam;
    protected final DbType dbType;
    
    @Override
    public Connection getConnection() throws SQLException {
        // 核心: 每次都通过 Processor 创建新连接
        return DataSourceProcessorProvider
            .getDataSourceProcessor(dbType)
            .getConnection(baseConnectionParam);
    }
    
    @Override
    public void close() {
        // 空实现: 临时连接用完即销毁
    }
}
```

**特点**：
- ✅ 每次调用都创建新连接
- ✅ 不维护连接池
- ✅ 直接调用 `DataSourceProcessor.getConnection()`
- ✅ 适合测试连接、低频查询

---

**路径 2: Pooled 池化连接**

```java
public abstract class BasePooledDataSourceClient implements PooledDataSourceClient {
    
    protected final BaseConnectionParam baseConnectionParam;
    protected HikariDataSource dataSource;  // 连接池
    
    public BasePooledDataSourceClient(
            BaseConnectionParam params, DbType dbType) {
        
        this.baseConnectionParam = params;
        // 构造时创建连接池
        this.dataSource = createDataSourcePool(params, dbType);
    }
    
    @Override
    public HikariDataSource createDataSourcePool(
            BaseConnectionParam params, DbType dbType) {
        
        // 创建 HikariCP 连接池
        HikariDataSource ds = new HikariDataSource();
        
        // 配置连接参数
        ds.setDriverClassName(params.getDriverClassName());
        ds.setJdbcUrl(DataSourceUtils.getJdbcUrl(dbType, params));
        ds.setUsername(params.getUser());
        ds.setPassword(PasswordUtils.decodePassword(params.getPassword()));
        
        // 配置连接池参数
        ds.setMinimumIdle(5);            // 最小空闲连接
        ds.setMaximumPoolSize(50);       // 最大连接数
        ds.setConnectionTestQuery(params.getValidationQuery());
        
        return ds;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        // 核心: 从连接池获取连接（复用）
        return dataSource.getConnection();
    }
    
    @Override
    public void close() {
        // 关闭连接池
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
```

**特点**：
- ✅ 维护 HikariCP 连接池
- ✅ `getConnection()` 从池中获取（复用）
- ✅ 初始化时调用 `DataSourceProcessor.getConnection()` 创建初始连接
- ✅ 适合生产环境、高频访问

---

#### 2.3 核心抽象 - DataSourceProcessor

```java
public interface DataSourceProcessor {
    
    // 核心方法: 获取数据库连接
    Connection getConnection(ConnectionParam params) throws Exception;
    
    // 辅助方法
    String getJdbcUrl(ConnectionParam params);
    String getDatasourceDriver();
    String getValidationQuery();
    
    // 其他方法
    ConnectionParam createConnectionParams(BaseDataSourceParamDTO dto);
    void checkDatasourceParam(BaseDataSourceParamDTO dto);
    boolean checkDataSourceConnectivity(ConnectionParam params);
}
```

**职责**：
- 🎯 **适配器模式**：适配不同数据库的差异
- 🎯 **策略模式**：每个数据源提供一种连接策略
- 🎯 **核心抽象**：统一 `getConnection()` 接口

---

### 第三层：插件适配层 (dolphinscheduler-datasource-*)

#### 职责：适配 Adapt（适配特定数据库）

#### MySQL 实现示例

```java
@AutoService(DataSourceProcessor.class)
public class MySQLDataSourceProcessor extends AbstractDataSourceProcessor {
    
    @Override
    public Connection getConnection(ConnectionParam connectionParam) throws Exception {
        MySQLConnectionParam params = (MySQLConnectionParam) connectionParam;
        
        // 1. 加载驱动
        Class.forName(getDatasourceDriver());
        
        // 2. 构建 JDBC URL
        String jdbcUrl = getJdbcUrl(params);
        // jdbc:mysql://localhost:3306/test_db?useUnicode=true&characterEncoding=UTF-8
        
        // 3. 获取连接
        Connection connection = DriverManager.getConnection(
            jdbcUrl,
            params.getUser(),
            params.getPassword()
        );
        
        return connection;
    }
    
    @Override
    public String getDatasourceDriver() {
        return "com.mysql.cj.jdbc.Driver";
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1";
    }
    
    @Override
    public String getJdbcUrl(ConnectionParam connectionParam) {
        MySQLConnectionParam params = (MySQLConnectionParam) connectionParam;
        
        StringBuilder jdbcUrl = new StringBuilder();
        jdbcUrl.append("jdbc:mysql://")
               .append(params.getAddress())
               .append("/")
               .append(params.getDatabase());
        
        // 添加参数
        if (params.getOther() != null) {
            jdbcUrl.append("?")
                   .append(transformOther(params.getOther()));
        }
        
        return jdbcUrl.toString();
    }
    
    @Override
    public DbType getDbType() {
        return DbType.MYSQL;
    }
}
```

#### PostgreSQL 实现示例

```java
@AutoService(DataSourceProcessor.class)
public class PostgreSQLDataSourceProcessor extends AbstractDataSourceProcessor {
    
    @Override
    public String getDatasourceDriver() {
        return "org.postgresql.Driver";
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1";  // PostgreSQL 也是 SELECT 1
    }
    
    @Override
    public String getJdbcUrl(ConnectionParam connectionParam) {
        // jdbc:postgresql://localhost:5432/test_db
        // ...
    }
}
```

#### Oracle 实现示例

```java
@AutoService(DataSourceProcessor.class)
public class OracleDataSourceProcessor extends AbstractDataSourceProcessor {
    
    @Override
    public String getDatasourceDriver() {
        return "oracle.jdbc.OracleDriver";
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1 FROM DUAL";  // Oracle 特有语法
    }
    
    @Override
    public String getJdbcUrl(ConnectionParam connectionParam) {
        // jdbc:oracle:thin:@localhost:1521:orcl
        // ...
    }
}
```

**特点**：
- ✅ 每个数据源独立实现
- ✅ 通过 `@AutoService` 自动注册
- ✅ 适配数据库特有的差异（驱动、URL、验证查询）
- ✅ 新增数据源不影响现有代码

---

## 🔄 完整调用流程

### 流程 1: AdHoc 临时连接

```
┌─────────────────────────────────────────────────────────────┐
│ 业务代码                                                     │
│   Connection conn = DataSourceClientProvider                │
│       .getAdHocConnection(DbType.MYSQL, params);            │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ DataSourceClientProvider.getAdHocConnection()               │
│   1. 获取 DataSourceChannel                                 │
│      channel = datasourceChannelMap.get("mysql")            │
│      → 返回 MySQLDataSourceChannel                          │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQLDataSourceChannel.createAdHocDataSourceClient()        │
│   2. 创建临时客户端                                          │
│      return new MySQLAdHocDataSourceClient(params, dbType)  │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQLAdHocDataSourceClient.getConnection()                  │
│   3. 调用父类方法                                            │
│      BaseAdHocDataSourceClient.getConnection()              │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ BaseAdHocDataSourceClient.getConnection()                   │
│   4. 获取 DataSourceProcessor                               │
│      processor = DataSourceProcessorProvider                │
│          .getDataSourceProcessor(DbType.MYSQL)              │
│      → 返回 MySQLDataSourceProcessor                        │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQLDataSourceProcessor.getConnection(params)              │
│   5. 创建新连接                                              │
│      Class.forName("com.mysql.cj.jdbc.Driver")             │
│      String url = "jdbc:mysql://localhost:3306/db"         │
│      Connection conn = DriverManager.getConnection(...)     │
│   6. 返回新连接                                              │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQL Database                                              │
│   建立 TCP 连接 → 握手 → 认证 → 返回 Connection             │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ 业务代码                                                     │
│   使用 Connection 执行 SQL                                   │
│   conn.close() → 连接真正关闭                                │
└─────────────────────────────────────────────────────────────┘
```

---

### 流程 2: Pooled 池化连接

```
┌─────────────────────────────────────────────────────────────┐
│ 业务代码                                                     │
│   Connection conn = DataSourceClientProvider                │
│       .getPooledConnection(DbType.MYSQL, params);           │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ DataSourceClientProvider.getPooledConnection()              │
│   1. 检查缓存                                                │
│      key = getDatasourceUniqueId(params, dbType)            │
│      client = POOLED_DATASOURCE_CLIENT_CACHE.get(key)       │
└───────────────────────┬─────────────────────────────────────┘
                        │
                    缓存未命中
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ DataSourceClientProvider (续)                               │
│   2. 获取 DataSourceChannel                                 │
│      channel = datasourceChannelMap.get("mysql")            │
│   3. 创建池化客户端                                          │
│      client = channel.createPooledDataSourceClient(...)     │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQLPooledDataSourceClient 构造函数                         │
│   4. 调用 createDataSourcePool()                            │
│      this.dataSource = createDataSourcePool(params, dbType) │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ BasePooledDataSourceClient.createDataSourcePool()           │
│   5. 创建 HikariCP 连接池                                    │
│      HikariDataSource ds = new HikariDataSource()           │
│      ds.setDriverClassName(...)                             │
│      ds.setJdbcUrl(...)                                     │
│      ds.setMinimumIdle(5)                                   │
│      ds.setMaximumPoolSize(50)                              │
│   6. 初始化连接池（内部调用 Processor.getConnection()）      │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ DataSourceClientProvider (续)                               │
│   7. 保存到缓存                                              │
│      POOLED_DATASOURCE_CLIENT_CACHE.put(key, client)        │
│   8. 获取连接                                                │
│      client.getConnection()                                 │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ MySQLPooledDataSourceClient.getConnection()                 │
│   9. 从连接池获取                                            │
│      return dataSource.getConnection()                      │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ HikariDataSource.getConnection()                            │
│   10. 检查池中是否有空闲连接                                 │
│       有 → 返回现有连接（复用）                              │
│       无 → 创建新连接 → 返回                                 │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│ 业务代码                                                     │
│   使用 Connection 执行 SQL                                   │
│   conn.close() → 连接归还到池中（不是真正关闭）              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🎯 灵活性体现

### 1. 数据源可插拔

**新增数据源只需三步**：

```java
// 步骤 1: 实现 DataSourceProcessor
@AutoService(DataSourceProcessor.class)
public class TiDBDataSourceProcessor extends AbstractDataSourceProcessor {
    
    @Override
    public Connection getConnection(ConnectionParam params) {
        // TiDB 特定的连接逻辑
        // TiDB 兼容 MySQL 协议，可以复用 MySQL 驱动
        return ...;
    }
    
    @Override
    public String getDatasourceDriver() {
        return "com.mysql.cj.jdbc.Driver";
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1";
    }
    
    @Override
    public DbType getDbType() {
        return DbType.TIDB;
    }
}

// 步骤 2: 实现 DataSourceChannelFactory
@AutoService(DataSourceChannelFactory.class)
public class TiDBDataSourceChannelFactory implements DataSourceChannelFactory {
    @Override
    public String getName() {
        return DbType.TIDB.getName();
    }
    
    @Override
    public DataSourceChannel create() {
        return new TiDBDataSourceChannel();
    }
}

// 步骤 3: 实现 DataSourceChannel
public class TiDBDataSourceChannel implements DataSourceChannel {
    @Override
    public AdHocDataSourceClient createAdHocDataSourceClient(...) {
        return new TiDBAdHocDataSourceClient(...);
    }
    
    @Override
    public PooledDataSourceClient createPooledDataSourceClient(...) {
        return new TiDBPooledDataSourceClient(...);
    }
}
```

**完成！** 系统会自动发现并加载 TiDB 插件。

---

### 2. 连接策略可选

业务代码根据场景选择：

```java
// 场景 1: 测试连接（低频）
public boolean testConnection(ConnectionParam params) {
    try (Connection conn = DataSourceClientProvider.getAdHocConnection(
            DbType.MYSQL, params)) {
        Statement stmt = conn.createStatement();
        stmt.executeQuery("SELECT 1");
        return true;
    } catch (Exception e) {
        return false;
    }
}

// 场景 2: 数据查询（高频）
public List<User> queryUsers() {
    try (Connection conn = DataSourceClientProvider.getPooledConnection(
            DbType.MYSQL, params)) {
        // 从连接池获取，性能高
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM users");
        // 处理结果...
    }
}
```

---

### 3. 实现方式可定制

**示例：定制 MySQL 连接逻辑**

```java
@AutoService(DataSourceProcessor.class)
public class OptimizedMySQLDataSourceProcessor extends MySQLDataSourceProcessor {
    
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(10)  // 高优先级，覆盖默认实现
            .build();
    }
    
    @Override
    public Connection getConnection(ConnectionParam params) throws Exception {
        // 自定义连接逻辑
        Connection conn = super.getConnection(params);
        
        // 添加额外配置
        Statement stmt = conn.createStatement();
        stmt.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES'");
        
        return conn;
    }
}
```

---

### 4. 连接池可配置

```java
public class CustomMySQLPooledDataSourceClient extends BasePooledDataSourceClient {
    
    @Override
    public HikariDataSource createDataSourcePool(
            BaseConnectionParam params, DbType dbType) {
        
        HikariDataSource ds = super.createDataSourcePool(params, dbType);
        
        // 定制连接池参数
        ds.setMinimumIdle(10);           // 调整最小空闲连接
        ds.setMaximumPoolSize(100);      // 调整最大连接数
        ds.setConnectionTimeout(10000);  // 连接超时时间
        ds.setIdleTimeout(300000);       // 空闲超时时间
        
        // 添加 MySQL 特定配置
        ds.addDataSourceProperty("cachePrepStmts", "true");
        ds.addDataSourceProperty("prepStmtCacheSize", "250");
        
        return ds;
    }
}
```

---

### 5. 优先级可覆盖

```java
// 默认实现
@AutoService(DataSourceProcessor.class)
public class MySQLDataSourceProcessor {
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(0)  // 默认优先级
            .build();
    }
}

// 增强实现
@AutoService(DataSourceProcessor.class)
public class EnhancedMySQLDataSourceProcessor {
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(100)  // 高优先级
            .build();
    }
    
    // 提供增强的实现...
}

// 结果：系统会使用 EnhancedMySQLDataSourceProcessor
```

---

## 🎨 设计模式总结

### 应用的设计模式

| 设计模式 | 应用位置 | 作用 |
|---------|---------|------|
| **SPI 模式** | PrioritySPI + ServiceLoader | 插件自动发现和加载 |
| **门面模式** | DataSourceClientProvider | 简化客户端调用 |
| **策略模式** | AdHoc vs Pooled | 不同连接获取策略 |
| **抽象工厂** | DataSourceChannel | 创建产品族 |
| **适配器模式** | DataSourceProcessor | 适配不同数据库 |
| **模板方法** | BasePooledDataSourceClient | 定义算法骨架 |
| **单例模式** | DataSourcePluginManager | 全局唯一管理器 |
| **代理模式** | HikariDataSource | 连接池代理 |

---

## 📝 关键设计原则

### SOLID 原则

✅ **单一职责 (SRP)**
- SPI 层：定义契约
- API 层：实现策略
- Plugin 层：适配数据库

✅ **开闭原则 (OCP)**
- 对扩展开放：新增数据源
- 对修改关闭：不改核心代码

✅ **里氏替换 (LSP)**
- 所有 DataSourceProcessor 可互换
- 行为一致性

✅ **接口隔离 (ISP)**
- AdHoc 和 Pooled 分离
- 职责清晰

✅ **依赖倒置 (DIP)**
- 依赖抽象接口
- 不依赖具体实现

---

## 🚀 优势总结

### 架构优势

✅ **高内聚低耦合**
- 三层职责清晰
- 层与层之间通过接口交互

✅ **易于扩展**
- 新增数据源只需实现接口
- 无需修改核心代码

✅ **灵活配置**
- 连接策略可选
- 连接池可定制
- 优先级可覆盖

✅ **性能优化**
- 连接池复用
- 客户端缓存
- 懒加载机制

✅ **易于测试**
- 每层可独立测试
- 接口便于 Mock

---

## 📚 总结

### 核心思想

```
getConnection() 不只是一个方法
而是一个完整的架构设计

通过三层协作:
• SPI 层定义契约
• API 层提供实现
• Plugin 层适配数据库

实现了:
• 数据源可插拔
• 策略可选择
• 实现可定制
• 连接池可配置
• 优先级可覆盖
```

### 最终效果

无论是 MySQL、PostgreSQL、Oracle 还是 Hive，
无论是临时连接还是池化连接，
业务代码都可以通过统一的 API 获取连接：

```java
Connection conn = DataSourceClientProvider.getConnection(...);
```

**这就是优雅的架构设计！** 🎉

---

**文档最后更新**: 2025-12-04

