# DolphinScheduler SPI 完整架构详解

## 📋 核心组件全景图

现在架构图包含了 **SPI 层的 10 个核心组件**：

### SPI 层 (dolphinscheduler-spi) 组件清单

```
1. SPI 插件机制
   ├── PrioritySPI (SPI 基础接口)
   ├── SPIIdentify (插件标识)
   └── PrioritySPIFactory (SPI 加载器)

2. 数据源 SPI 扩展点
   ├── DataSourceChannelFactory (Channel 工厂接口)
   └── DataSourceChannel (数据源通道接口)

3. 客户端接口定义
   ├── DataSourceClient (客户端接口)
   ├── AdHocDataSourceClient (临时客户端接口)
   └── PooledDataSourceClient (池化客户端接口)

4. 连接参数定义
   ├── ConnectionParam (连接参数接口)
   └── BaseConnectionParam (连接参数基类)
```

---

## 🔗 核心组件详解

### 1️⃣ PrioritySPI - SPI 基础接口

```java
public interface PrioritySPI extends Comparable<Integer> {
    // 获取插件标识（名称 + 优先级）
    SPIIdentify getIdentify();
    
    // 支持优先级比较
    default int compareTo(Integer o) {
        return Integer.compare(getIdentify().getPriority(), o);
    }
}
```

**职责**：
- ✅ 所有 SPI 插件的基础接口
- ✅ 提供插件身份标识
- ✅ 支持优先级比较和覆盖

**关键关系**：
```
PrioritySPI
    ↓ extends
DataSourceChannelFactory (数据源工厂)
    ↓ implements
MySQLDataSourceChannelFactory (MySQL 工厂)
PostgreSQLDataSourceChannelFactory (PostgreSQL 工厂)
...
```

---

### 2️⃣ SPIIdentify - 插件标识

```java
@Data
@Builder
@AllArgsConstructor
public class SPIIdentify {
    private String name;              // 插件名称，如 "mysql"
    private int priority = 0;         // 优先级，默认 0
}
```

**职责**：
- ✅ 封装插件的身份信息
- ✅ 支持同名插件通过优先级区分

**使用示例**：
```java
@AutoService(DataSourceChannelFactory.class)
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(0)
            .build();
    }
}

// 增强版本（高优先级）
@AutoService(DataSourceChannelFactory.class)
public class EnhancedMySQLFactory implements DataSourceChannelFactory {
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(100)  // 高优先级，会覆盖默认实现
            .build();
    }
}
```

---

### 3️⃣ PrioritySPIFactory - SPI 加载器

```java
public class PrioritySPIFactory<T extends PrioritySPI> {
    private final Map<String, T> map = new HashMap<>();
    
    public PrioritySPIFactory(Class<T> spiClass) {
        // 通过 ServiceLoader 自动发现所有实现
        for (T t : ServiceLoader.load(spiClass)) {
            if (map.containsKey(t.getIdentify().getName())) {
                resolveConflict(t);  // 处理同名冲突
            } else {
                map.put(t.getIdentify().getName(), t);
            }
        }
    }
    
    private void resolveConflict(T newSPI) {
        T oldSPI = map.get(newSPI.getIdentify().getName());
        
        if (newSPI.compareTo(oldSPI.getIdentify().getPriority()) == 0) {
            throw new IllegalArgumentException("同名同优先级冲突");
        } else if (newSPI.compareTo(oldSPI.getIdentify().getPriority()) > 0) {
            map.put(newSPI.getIdentify().getName(), newSPI);  // 高优先级覆盖
            log.info("高优先级插件 {} 覆盖 {}", newSPI, oldSPI);
        } else {
            log.info("低优先级插件 {} 被忽略", newSPI);
        }
    }
}
```

**职责**：
- ✅ 自动发现所有 SPI 插件
- ✅ 处理优先级冲突
- ✅ 返回不可变的插件 Map

**使用场景**：
```java
// 加载 DataSourceChannelFactory
PrioritySPIFactory<DataSourceChannelFactory> factory = 
    new PrioritySPIFactory<>(DataSourceChannelFactory.class);
Map<String, DataSourceChannelFactory> factoryMap = factory.getSPIMap();

// 加载 DataSourceProcessor
PrioritySPIFactory<DataSourceProcessor> processorFactory = 
    new PrioritySPIFactory<>(DataSourceProcessor.class);
Map<String, DataSourceProcessor> processorMap = processorFactory.getSPIMap();
```

---

### 4️⃣ DataSourceChannelFactory - Channel 工厂接口

```java
public interface DataSourceChannelFactory extends PrioritySPI {
    // 数据源名称（如 "mysql"）
    String getName();
    
    // 创建 DataSourceChannel
    DataSourceChannel create();
    
    // 实现 PrioritySPI 的方法
    @Override
    default SPIIdentify getIdentify() {
        return SPIIdentify.builder().name(getName()).build();
    }
}
```

**职责**：
- ✅ SPI 扩展点，每个数据源提供一个实现
- ✅ 继承 PrioritySPI，具备插件能力
- ✅ 创建对应的 DataSourceChannel

**关键关系**：
```
DataSourceChannelFactory
    ↓ create()
DataSourceChannel
    ↓ createXxxClient()
DataSourceClient (AdHoc/Pooled)
```

---

### 5️⃣ DataSourceChannel - 数据源通道接口

```java
public interface DataSourceChannel {
    // 创建临时连接客户端
    AdHocDataSourceClient createAdHocDataSourceClient(
        BaseConnectionParam baseConnectionParam, 
        DbType dbType);
    
    // 创建池化连接客户端
    PooledDataSourceClient createPooledDataSourceClient(
        BaseConnectionParam baseConnectionParam, 
        DbType dbType);
}
```

**职责**：
- ✅ 抽象工厂，创建两种策略的客户端
- ✅ 接收 BaseConnectionParam 作为参数
- ✅ 每个数据源提供具体实现

**实现示例**：
```java
public class MySQLDataSourceChannel implements DataSourceChannel {
    @Override
    public AdHocDataSourceClient createAdHocDataSourceClient(
            BaseConnectionParam params, DbType dbType) {
        return new MySQLAdHocDataSourceClient(params, dbType);
    }
    
    @Override
    public PooledDataSourceClient createPooledDataSourceClient(
            BaseConnectionParam params, DbType dbType) {
        return new MySQLPooledDataSourceClient(params, dbType);
    }
}
```

**关键特点**：
- 🎯 接收 **BaseConnectionParam** 参数
- 🎯 根据参数创建对应的客户端
- 🎯 分离两种连接策略

---

### 6️⃣ BaseConnectionParam - 连接参数基类

```java
@Data
@JsonInclude(Include.NON_NULL)
public abstract class BaseConnectionParam implements ConnectionParam {
    protected String user;              // 用户名
    protected String password;          // 密码
    protected String address;           // 地址 (host:port)
    protected String database;          // 数据库名
    protected String jdbcUrl;           // JDBC URL
    protected String driverLocation;    // 驱动位置
    protected String driverClassName;   // 驱动类名
    protected String validationQuery;   // 验证查询
    protected String compatibleMode;    // 兼容模式
    protected Map<String, String> other; // 扩展参数
}
```

**职责**：
- ✅ 封装数据库连接所需的所有参数
- ✅ 支持扩展参数（other Map）
- ✅ 被所有数据源插件使用

**子类示例**：
```java
public class MySQLConnectionParam extends BaseConnectionParam {
    // MySQL 特有参数
    private String loadBalanceStrategy;
    
    @Override
    public String toString() {
        return JSONUtils.toJsonString(this);
    }
}

public class OracleConnectionParam extends BaseConnectionParam {
    // Oracle 特有参数
    private String oracleDb;
    private String connectType;  // SID or SERVICE_NAME
}
```

**参数传递流程**：
```
业务代码构建参数
    ↓
BaseConnectionParam params = new MySQLConnectionParam();
params.setUser("root");
params.setPassword("123456");
params.setAddress("localhost:3306");
params.setDatabase("test_db");
    ↓ 传递给
DataSourceChannel.createPooledClient(params, dbType)
    ↓ 传递给
MySQLPooledDataSourceClient(params, dbType)
    ↓ 传递给
DataSourceProcessor.getConnection(params)
    ↓ 使用参数
DriverManager.getConnection(params.getJdbcUrl(), params.getUser(), ...)
```

---

## 🔗 完整的组件关系图

### 架构层次

```
┌──────────────────────────────────────────────────────────────┐
│ SPI 层 (dolphinscheduler-spi)                                 │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 1. SPI 插件机制                                          │ │
│ │    PrioritySPI ← 所有 SPI 的基础                         │ │
│ │        ↓ 使用                                            │ │
│ │    SPIIdentify ← 插件标识 (name + priority)             │ │
│ │        ↑ 管理                                            │ │
│ │    PrioritySPIFactory ← SPI 加载器                       │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                               │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 2. 数据源 SPI 扩展点                                     │ │
│ │    DataSourceChannelFactory ← SPI 扩展点                │ │
│ │        ├─ 继承 PrioritySPI                              │ │
│ │        └─ create() → DataSourceChannel                  │ │
│ │                                                          │ │
│ │    DataSourceChannel ← 通道接口                         │ │
│ │        ├─ createAdHocClient(BaseConnectionParam)        │ │
│ │        └─ createPooledClient(BaseConnectionParam)       │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                               │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 3. 客户端接口定义                                        │ │
│ │    DataSourceClient ← 客户端基础接口                    │ │
│ │        ├─ getConnection(): Connection                    │ │
│ │        └─ close(): void                                  │ │
│ │            ↓ extends                                     │ │
│ │    ├─ AdHocDataSourceClient ← 临时连接                  │ │
│ │    └─ PooledDataSourceClient ← 池化连接                 │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                               │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 4. 连接参数定义                                          │ │
│ │    ConnectionParam ← 参数接口                           │ │
│ │        ↓ implements                                      │ │
│ │    BaseConnectionParam ← 参数基类                       │ │
│ │        ├─ user, password, address                        │ │
│ │        ├─ database, jdbcUrl                             │ │
│ │        ├─ driverClassName, validationQuery              │ │
│ │        └─ other: Map<String, String>                    │ │
│ └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

---

## 🎯 组件协作关系

### 关系 1: PrioritySPI 体系

```
PrioritySPI (基础接口)
    ├─ 使用 SPIIdentify (插件标识)
    │     ├─ name: String
    │     └─ priority: int
    │
    ├─ 被继承 ← DataSourceChannelFactory
    │
    └─ 被管理 ← PrioritySPIFactory (加载器)
           └─ ServiceLoader.load() 自动发现
```

**工作流程**：
```java
// 1. 定义 SPI 接口
public interface DataSourceChannelFactory extends PrioritySPI {
    // ...
}

// 2. 实现插件
@AutoService(DataSourceChannelFactory.class)
public class MySQLFactory implements DataSourceChannelFactory {
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name("mysql")
            .priority(0)
            .build();
    }
}

// 3. 自动加载
PrioritySPIFactory<DataSourceChannelFactory> factory = 
    new PrioritySPIFactory<>(DataSourceChannelFactory.class);
// ServiceLoader 自动发现所有 @AutoService 标注的实现
```

---

### 关系 2: DataSourceChannelFactory 创建 DataSourceChannel

```
DataSourceChannelFactory (工厂)
    ├─ getName() → "mysql"
    ├─ create() → DataSourceChannel
    └─ getIdentify() → SPIIdentify
        ↓ create()
DataSourceChannel (通道)
    ├─ createAdHocClient(BaseConnectionParam, DbType)
    └─ createPooledClient(BaseConnectionParam, DbType)
```

**工作流程**：
```java
// 1. PluginManager 加载 Factory
PrioritySPIFactory<DataSourceChannelFactory> factory = 
    new PrioritySPIFactory<>(DataSourceChannelFactory.class);

// 2. 遍历所有 Factory
for (DataSourceChannelFactory f : factory.getSPIMap().values()) {
    // 3. 调用 create() 创建 Channel
    DataSourceChannel channel = f.create();
    
    // 4. 保存到 Map
    datasourceChannelMap.put(f.getName(), channel);
}
```

---

### 关系 3: DataSourceChannel 使用 BaseConnectionParam

```
BaseConnectionParam (连接参数)
    ├─ user: String
    ├─ password: String
    ├─ address: String
    ├─ database: String
    ├─ jdbcUrl: String
    ├─ driverClassName: String
    ├─ validationQuery: String
    └─ other: Map<String, String>
        ↓ 传递给
DataSourceChannel
    ├─ createAdHocClient(BaseConnectionParam, DbType)
    │       ↓
    │   AdHocDataSourceClient (使用参数连接数据库)
    │
    └─ createPooledClient(BaseConnectionParam, DbType)
            ↓
        PooledDataSourceClient (使用参数初始化连接池)
```

**参数使用流程**：
```java
// 1. 业务代码构建参数
MySQLConnectionParam params = new MySQLConnectionParam();
params.setUser("root");
params.setPassword("123456");
params.setAddress("localhost:3306");
params.setDatabase("test_db");
params.setDriverClassName("com.mysql.cj.jdbc.Driver");
params.setValidationQuery("SELECT 1");

// 2. 传递给 Channel
DataSourceChannel channel = datasourceChannelMap.get("mysql");
PooledDataSourceClient client = channel.createPooledDataSourceClient(params, DbType.MYSQL);

// 3. Client 使用参数创建连接池
HikariDataSource ds = new HikariDataSource();
ds.setDriverClassName(params.getDriverClassName());
ds.setJdbcUrl(params.getJdbcUrl());
ds.setUsername(params.getUser());
ds.setPassword(params.getPassword());
ds.setConnectionTestQuery(params.getValidationQuery());

// 4. 从连接池获取连接
Connection conn = client.getConnection();
```

---

### 关系 4: DataSourceClient 体系

```
DataSourceClient (客户端接口)
    ├─ getConnection(): Connection
    └─ close(): void
        ↓ extends
    ├── AdHocDataSourceClient (临时客户端)
    │       ↓ implements
    │   BaseAdHocDataSourceClient
    │       ├─ 接收 BaseConnectionParam
    │       ├─ 每次创建新连接
    │       └─ 调用 DataSourceProcessor
    │
    └── PooledDataSourceClient (池化客户端)
            ↓ implements
        BasePooledDataSourceClient
            ├─ 接收 BaseConnectionParam
            ├─ 维护 HikariDataSource
            └─ 从连接池获取连接
```

---

## 🌐 四大管理器完整协作

### 管理器关系图

```
┌──────────────────────────────────────────────────────────┐
│              【管理层 - 四大管理器】                       │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  DataSourceClientProvider (门面 - 业务入口)              │
│      │                                                    │
│      ├─→ DataSourcePluginManager (管理 Channel)          │
│      │       │                                            │
│      │       ├─→ PrioritySPIFactory                       │
│      │       │       └─→ 加载 DataSourceChannelFactory   │
│      │       │                                            │
│      │       └─→ Map<String, DataSourceChannel>          │
│      │                                                    │
│      └─→ DataSourceProcessorProvider (提供 Processor)    │
│              │                                            │
│              └─→ DataSourceProcessorManager              │
│                      │                                    │
│                      ├─→ PrioritySPIFactory               │
│                      │       └─→ 加载 DataSourceProcessor│
│                      │                                    │
│                      └─→ Map<String, DataSourceProcessor>│
│                                                           │
└──────────────────────────────────────────────────────────┘
```

### 系统启动时的协作

```
系统启动
    │
    ├─→ DataSourcePluginManager.installPlugin()
    │       │
    │       ├─→ new PrioritySPIFactory<>(DataSourceChannelFactory.class)
    │       │       └─→ ServiceLoader 发现所有 Factory
    │       │
    │       ├─→ 遍历所有 Factory
    │       │       └─→ factory.create() → DataSourceChannel
    │       │
    │       └─→ 保存到 datasourceChannelMap
    │           {"mysql": MySQLChannel, "postgresql": PGChannel, ...}
    │
    └─→ DataSourceProcessorManager.installProcessor()
            │
            ├─→ new PrioritySPIFactory<>(DataSourceProcessor.class)
            │       └─→ ServiceLoader 发现所有 Processor
            │
            └─→ 保存到 dataSourceProcessorMap
                {"MYSQL": MySQLProcessor, "POSTGRESQL": PGProcessor, ...}
```

### 业务调用时的协作

```
业务代码
    ↓ 调用
DataSourceClientProvider.getPooledConnection(DbType.MYSQL, params)
    │
    ├─→ 步骤1: 获取 Channel
    │   DataSourcePluginManager.getDataSourceChannelMap().get("mysql")
    │       → MySQLDataSourceChannel
    │
    ├─→ 步骤2: 创建 Client
    │   MySQLChannel.createPooledDataSourceClient(params, DbType.MYSQL)
    │       → MySQLPooledDataSourceClient
    │
    ├─→ 步骤3: 初始化连接池
    │   new HikariDataSource()
    │   配置参数 (从 BaseConnectionParam 获取)
    │
    ├─→ 步骤4: 获取 Processor (AdHoc 路径需要)
    │   DataSourceProcessorProvider.getDataSourceProcessor(DbType.MYSQL)
    │       ↓
    │   DataSourceProcessorManager.get("MYSQL")
    │       → MySQLDataSourceProcessor
    │
    └─→ 步骤5: 获取连接
        Client.getConnection()
            ↓ (Pooled)
        HikariDataSource.getConnection()
            ↓ (AdHoc)
        Processor.getConnection(params)
            ↓
        Connection
```

---

## 📊 新增组件在架构中的位置

### 完整的类层次结构

```
【SPI 层】dolphinscheduler-spi
    │
    ├─ SPI 插件机制
    │   ├─ PrioritySPI ············· 所有 SPI 的基础接口
    │   ├─ SPIIdentify ············· 插件标识 (name + priority)
    │   └─ PrioritySPIFactory ······ SPI 加载器 (ServiceLoader)
    │
    ├─ 数据源 SPI 扩展点
    │   ├─ DataSourceChannelFactory  SPI 扩展点（继承 PrioritySPI）
    │   └─ DataSourceChannel ········ 通道接口（抽象工厂）
    │
    ├─ 客户端接口
    │   ├─ DataSourceClient ········· 客户端基础接口
    │   ├─ AdHocDataSourceClient ···· 临时客户端接口
    │   └─ PooledDataSourceClient ··· 池化客户端接口
    │
    └─ 连接参数
        ├─ ConnectionParam ·········· 参数接口
        └─ BaseConnectionParam ······ 参数基类
            ├─ user, password, address
            ├─ database, jdbcUrl
            ├─ driverClassName
            └─ other: Map (扩展参数)

【API 层】dolphinscheduler-datasource-api
    │
    ├─ 管理层
    │   ├─ DataSourcePluginManager ·· 管理 DataSourceChannel
    │   ├─ DataSourceProcessorManager 管理 DataSourceProcessor
    │   ├─ DataSourceClientProvider · 客户端提供者 (门面)
    │   └─ DataSourceProcessorProvider 处理器提供者
    │
    ├─ 客户端实现
    │   ├─ BaseAdHocDataSourceClient  临时客户端基类
    │   └─ BasePooledDataSourceClient 池化客户端基类
    │
    └─ 处理器
        └─ DataSourceProcessor ······ 处理器接口

【插件层】dolphinscheduler-datasource-*
    │
    ├─ MySQL 插件
    │   ├─ MySQLDataSourceChannelFactory
    │   ├─ MySQLDataSourceChannel
    │   ├─ MySQLAdHocDataSourceClient
    │   ├─ MySQLPooledDataSourceClient
    │   ├─ MySQLDataSourceProcessor
    │   └─ MySQLConnectionParam (继承 BaseConnectionParam)
    │
    └─ ... 其他 27 种数据源
```

---

## 🔄 BaseConnectionParam 的核心作用

### 作用 1: 统一参数模型

所有数据源都使用统一的参数结构：

```java
// MySQL
MySQLConnectionParam params = new MySQLConnectionParam();
params.setUser("root");
params.setAddress("localhost:3306");

// PostgreSQL
PostgreSQLConnectionParam params = new PostgreSQLConnectionParam();
params.setUser("postgres");
params.setAddress("localhost:5432");

// Oracle
OracleConnectionParam params = new OracleConnectionParam();
params.setUser("system");
params.setAddress("localhost:1521");
```

### 作用 2: 跨层参数传递

```
业务层构建参数
    ↓
BaseConnectionParam params
    ↓ 传递到
DataSourceChannel.createXxxClient(params, dbType)
    ↓ 传递到
DataSourceClient 构造函数
    ↓ 传递到
DataSourceProcessor.getConnection(params)
    ↓ 使用参数
创建 Connection
```

### 作用 3: 扩展参数支持

```java
BaseConnectionParam params = new MySQLConnectionParam();

// 基础参数
params.setUser("root");
params.setPassword("123456");

// 扩展参数
Map<String, String> other = new HashMap<>();
other.put("useSSL", "false");
other.put("serverTimezone", "UTC");
other.put("allowPublicKeyRetrieval", "true");
params.setOther(other);

// DataSourceProcessor 使用时
String jdbcUrl = params.getJdbcUrl() + "?" + transformOther(params.getOther());
// jdbc:mysql://localhost:3306/db?useSSL=false&serverTimezone=UTC&...
```

---

## 🎨 完整的调用链路（包含所有新增组件）

### Pooled 连接完整路径

```
1. 业务代码
   Connection conn = DataSourceClientProvider
       .getPooledConnection(DbType.MYSQL, params);
   
2. DataSourceClientProvider (门面)
   ↓ 依赖
   DataSourcePluginManager
       ↓ 查询
       datasourceChannelMap.get("mysql")
           → MySQLDataSourceChannel
   
3. MySQLDataSourceChannel.createPooledClient(params, dbType)
   ↓ 使用 BaseConnectionParam
   new MySQLPooledDataSourceClient(params, dbType)
   
4. MySQLPooledDataSourceClient 构造函数
   ↓ 使用 BaseConnectionParam
   createDataSourcePool(params, dbType)
       ↓
       new HikariDataSource()
       ds.setDriverClassName(params.getDriverClassName())
       ds.setJdbcUrl(params.getJdbcUrl())
       ds.setUsername(params.getUser())
       ds.setPassword(params.getPassword())
   
5. Client.getConnection()
   ↓
   pool.getConnection()
   ↓
   Connection
```

### AdHoc 连接完整路径

```
1. 业务代码
   Connection conn = DataSourceClientProvider
       .getAdHocConnection(DbType.MYSQL, params);
   
2. DataSourceClientProvider (门面)
   ↓ 依赖
   DataSourcePluginManager
       ↓ 查询
       datasourceChannelMap.get("mysql")
           → MySQLDataSourceChannel
   
3. MySQLDataSourceChannel.createAdHocClient(params, dbType)
   ↓ 使用 BaseConnectionParam
   new MySQLAdHocDataSourceClient(params, dbType)
   
4. MySQLAdHocDataSourceClient.getConnection()
   ↓ 依赖
   DataSourceProcessorProvider.getDataSourceProcessor(DbType.MYSQL)
       ↓ 依赖
       DataSourceProcessorManager.get("MYSQL")
           → MySQLDataSourceProcessor
   
5. MySQLProcessor.getConnection(params)
   ↓ 使用 BaseConnectionParam
   Class.forName(params.getDriverClassName())
   DriverManager.getConnection(
       params.getJdbcUrl(),
       params.getUser(),
       params.getPassword()
   )
   ↓
   Connection
```

---

## 📚 总结

### 核心组件总数

- **SPI 层**: 10 个核心组件
- **API 层**: 7 个核心组件（4 个管理器 + 2 个客户端基类 + 1 个处理器）
- **插件层**: 每个数据源 5 个组件 × 28 = 140+ 组件

### 关键设计

1. **PrioritySPI** - 所有插件的基础，提供优先级机制
2. **PrioritySPIFactory** - 自动发现和加载插件
3. **DataSourceChannelFactory** - SPI 扩展点，创建 Channel
4. **DataSourceChannel** - 抽象工厂，创建两种客户端
5. **BaseConnectionParam** - 统一参数模型，跨层传递
6. **四大管理器** - 协作管理 Channel 和 Processor

### 核心优势

✅ **插件化**: 基于 SPI，自动发现  
✅ **可扩展**: 新增数据源无需改核心代码  
✅ **灵活性**: 优先级机制支持覆盖和增强  
✅ **统一性**: 参数模型统一，易于维护  
✅ **分层清晰**: 职责明确，易于理解  

---

现在架构图完整展示了 **SPI 机制的精髓**！🎉

