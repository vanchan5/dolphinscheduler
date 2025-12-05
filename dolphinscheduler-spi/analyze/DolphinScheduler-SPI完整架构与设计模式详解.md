# DolphinScheduler SPI 完整架构与设计模式详解

## 📋 目录
1. [完整类关系图](#一完整类关系图)
2. [核心类职责详解](#二核心类职责详解)
3. [设计模式分析](#三设计模式分析)
4. [完整调用流程](#四完整调用流程)
5. [实战代码示例](#五实战代码示例)

---

## 一、完整类关系图

### 1.1 全景架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           【SPI 基础层】                                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  PrioritySPI (interface)                                         │  │
│  │  - getIdentify(): SPIIdentify                                    │  │
│  │  - compareTo(Integer): int                                       │  │
│  │  【作用】所有 SPI 插件的基础接口，提供身份标识和优先级能力       │  │
│  └───────────────────────────┬──────────────────────────────────────┘  │
│                              │ extends                                  │
│  ┌───────────────────────────▼──────────────────────────────────────┐  │
│  │  DataSourceChannelFactory (interface)  【SPI 扩展点】            │  │
│  │  + getName(): String                                             │  │
│  │  + create(): DataSourceChannel                                   │  │
│  │  【作用】数据源工厂的 SPI 接口，负责创建 DataSourceChannel        │  │
│  └───────────────────────────┬──────────────────────────────────────┘  │
└──────────────────────────────┼───────────────────────────────────────────┘
                               │ implements
          ┌────────────────────┼────────────────────┐
          │                    │                    │
┌─────────▼────────┐  ┌────────▼────────┐  ┌──────▼─────────┐
│ MySQLDataSource  │  │ PostgreSQLData  │  │  SSHDataSource │
│ ChannelFactory   │  │ SourceChannel   │  │  Channel...    │
│                  │  │ Factory         │  │  Factory       │
│ @AutoService     │  │ @AutoService    │  │  @AutoService  │
└─────────┬────────┘  └────────┬────────┘  └──────┬─────────┘
          │ create()           │ create()          │ create()
          └────────────────────┼───────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────┐
│                        【数据源通道层】                                   │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  DataSourceChannel (interface)                                    │ │
│  │  + createAdHocDataSourceClient(params, dbType)                    │ │
│  │  + createPooledDataSourceClient(params, dbType)                   │ │
│  │  【作用】数据源通道，负责创建不同策略的客户端                      │ │
│  └───────────────────────────┬───────────────────────────────────────┘ │
│                              │ implements                                │
│         ┌────────────────────┼────────────────────┐                     │
│         │                    │                    │                     │
│  ┌──────▼──────┐   ┌─────────▼────────┐   ┌──────▼──────┐             │
│  │  MySQLData  │   │  PostgreSQLData  │   │  SSHData    │             │
│  │  Source     │   │  SourceChannel   │   │  Source     │             │
│  │  Channel    │   │                  │   │  Channel    │             │
│  └──────┬──────┘   └─────────┬────────┘   └──────┬──────┘             │
│         │                    │                    │                     │
└─────────┼────────────────────┼────────────────────┼─────────────────────┘
          │                    │                    │
          │ 创建                │                    │
          ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        【数据源客户端层】                                 │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  DataSourceClient (interface) extends AutoCloseable              │ │
│  │  + getConnection(): Connection                                    │ │
│  │  + close(): void                                                  │ │
│  │  【作用】数据源客户端的顶层接口，定义获取连接的契约                │ │
│  └───────────────┬───────────────────────┬───────────────────────────┘ │
│                  │ extends               │ extends                     │
│     ┌────────────▼─────────────┐  ┌──────▼──────────────────────┐     │
│     │ AdHocDataSourceClient    │  │ PooledDataSourceClient      │     │
│     │ (Marker Interface)       │  │ + createDataSourcePool()    │     │
│     │ 【非池化客户端标记】       │  │ 【池化客户端】                │     │
│     └────────────┬─────────────┘  └──────┬──────────────────────┘     │
│                  │ implements            │ implements                  │
│       ┌──────────┼──────────┐   ┌────────┼──────────┐                 │
│       │          │          │   │        │          │                 │
│  ┌────▼───┐ ┌───▼────┐ ┌───▼───▼┐ ┌─────▼────┐ ┌──▼─────┐           │
│  │ MySQL  │ │Postgre │ │  SSH   │ │ MySQL    │ │Postgre │           │
│  │ AdHoc  │ │SQL     │ │  AdHoc │ │ Pooled   │ │SQL     │           │
│  │ Client │ │AdHoc   │ │ Client │ │ Client   │ │Pooled  │           │
│  │        │ │Client  │ │        │ │          │ │Client  │           │
│  └────────┘ └────────┘ └────────┘ └──────────┘ └────────┘           │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        【SPI 管理层】                                     │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  PrioritySPIFactory<T extends PrioritySPI>                        │ │
│  │  - map: Map<String, T>                                            │ │
│  │  + PrioritySPIFactory(Class<T> spiClass)                          │ │
│  │  + getSPIMap(): Map<String, T>                                    │ │
│  │  - resolveConflict(T newSPI): void                                │ │
│  │                                                                    │ │
│  │  【核心流程】                                                       │ │
│  │  1. 构造时通过 ServiceLoader.load() 扫描所有实现类                 │ │
│  │  2. 处理同名插件的优先级冲突                                        │ │
│  │  3. 返回不可变的插件 Map                                           │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        【插件管理器】                                     │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  DataSourcePluginManager                                          │ │
│  │  - datasourceChannelMap: Map<String, DataSourceChannel>           │ │
│  │  + installPlugin(): void                                          │ │
│  │  + getDataSourceChannelMap(): Map<String, DataSourceChannel>      │ │
│  │                                                                    │ │
│  │  【职责】                                                           │ │
│  │  1. 使用 PrioritySPIFactory 加载所有 DataSourceChannelFactory     │ │
│  │  2. 调用每个 Factory 的 create() 创建 DataSourceChannel           │ │
│  │  3. 维护 name -> DataSourceChannel 的映射                         │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        【客户端提供者】                                   │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  DataSourceClientProvider                                         │ │
│  │  - dataSourcePluginManager: DataSourcePluginManager               │ │
│  │  - POOLED_DATASOURCE_CLIENT_CACHE: Cache<String, Client>          │ │
│  │  + getPooledDataSourceClient(dbType, params): Client              │ │
│  │  + getAdHocDataSourceClient(dbType, params): Client               │ │
│  │  + getPooledConnection(dbType, params): Connection                │ │
│  │  + getAdHocConnection(dbType, params): Connection                 │ │
│  │                                                                    │ │
│  │  【职责】业务代码的统一入口，提供简便的 API                          │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 客户端层次结构详解

```
                    DataSourceClient (interface)
                           │
                           │ 定义获取连接的契约
                           │
         ┌─────────────────┴─────────────────┐
         │                                   │
         │                                   │
AdHocDataSourceClient              PooledDataSourceClient
(Marker Interface)                 (interface)
【即用即销毁】                      【连接池化】
         │                                   │
         │                                   │
         ├── 基础实现                        ├── 基础实现
         │                                   │
BaseAdHocDataSourceClient          BasePooledDataSourceClient
(abstract class)                   (abstract class)
    │                                       │
    │ getConnection():                      │ dataSource: HikariDataSource
    │   - 调用 DataSourceProcessor          │
    │   - 每次创建新连接                     │ createDataSourcePool():
    │                                       │   - 创建 HikariDataSource
    │ close():                              │   - 配置连接池参数
    │   - 空实现（不需要关闭）               │
    │                                       │ getConnection():
    ├── MySQL 实现                          │   - 从连接池获取
    │                                       │
MySQLAdHocDataSourceClient         MySQLPooledDataSourceClient
    │                                       │
    ├── PostgreSQL 实现                     ├── PostgreSQL 实现
    │                                       │
PostgreSQLAdHocDataSourceClient    PostgreSQLPooledDataSourceClient
    │                                       │
    └── ... 更多数据源                      └── ... 更多数据源
```

---

## 二、核心类职责详解

### 2.1 PrioritySPI - SPI 基础接口

```java
public interface PrioritySPI extends Comparable<Integer> {
    SPIIdentify getIdentify();  // 插件身份标识（名称+优先级）
    
    default int compareTo(Integer o) {
        return Integer.compare(getIdentify().getPriority(), o);
    }
}
```

**角色**：SPI 插件的"身份证"

**职责**：
- ✅ 提供插件唯一标识（名称）
- ✅ 提供优先级信息
- ✅ 支持优先级比较

**类比**：像公司员工的工牌，记录身份和级别

---

### 2.2 PrioritySPIFactory - SPI 加载器

```java
public class PrioritySPIFactory<T extends PrioritySPI> {
    private final Map<String, T> map = new HashMap<>();
    
    public PrioritySPIFactory(Class<T> spiClass) {
        // 1. 通过 ServiceLoader 自动发现所有实现类
        for (T t : ServiceLoader.load(spiClass)) {
            if (map.containsKey(t.getIdentify().getName())) {
                resolveConflict(t);  // 2. 解决同名冲突
            } else {
                map.put(t.getIdentify().getName(), t);  // 3. 保存插件
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

**角色**：插件的"人事经理"

**职责**：
- ✅ 自动发现所有插件（通过 Java SPI）
- ✅ 处理优先级冲突
- ✅ 维护插件注册表

**类比**：像 HR，负责招聘、去重、管理员工

---

### 2.3 DataSourceChannelFactory - 数据源工厂接口

```java
public interface DataSourceChannelFactory extends PrioritySPI {
    String getName();                // 返回数据源类型，如 "mysql"
    DataSourceChannel create();       // 创建对应的 Channel
    
    @Override
    default SPIIdentify getIdentify() {
        return SPIIdentify.builder().name(getName()).build();
    }
}
```

**实际实现示例**：

```java
@AutoService(DataSourceChannelFactory.class)  // 自动注册 SPI
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    
    @Override
    public String getName() {
        return DbType.MYSQL.getName();  // "mysql"
    }
    
    @Override
    public DataSourceChannel create() {
        return new MySQLDataSourceChannel();
    }
}
```

**角色**：每个数据源的"工厂经理"

**职责**：
- ✅ 实现 PrioritySPI，具备 SPI 能力
- ✅ 提供数据源名称标识
- ✅ 创建对应的 DataSourceChannel

**类比**：像各个分厂的厂长，负责创建本厂的生产线

---

### 2.4 DataSourceChannel - 数据源通道

```java
public interface DataSourceChannel {
    // 创建临时连接客户端（不池化）
    AdHocDataSourceClient createAdHocDataSourceClient(
        BaseConnectionParam params, DbType dbType);
    
    // 创建池化连接客户端（连接池）
    PooledDataSourceClient createPooledDataSourceClient(
        BaseConnectionParam params, DbType dbType);
}
```

**实际实现示例**：

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

**角色**：数据源的"生产线"

**职责**：
- ✅ 创建两种策略的客户端
  - AdHoc：即用即销毁，适合偶尔查询
  - Pooled：连接池化，适合高频访问
- ✅ 封装数据源连接创建逻辑

**类比**：像生产线，根据需求生产不同类型的产品

---

### 2.5 DataSourceClient 体系 - 客户端接口

#### 2.5.1 DataSourceClient - 顶层接口

```java
public interface DataSourceClient extends AutoCloseable {
    Connection getConnection() throws SQLException;
    void close() throws Exception;
}
```

**角色**：客户端的"抽象规范"

**职责**：
- ✅ 定义获取连接的契约
- ✅ 实现 AutoCloseable，支持资源自动释放

---

#### 2.5.2 AdHocDataSourceClient - 临时客户端

```java
public interface AdHocDataSourceClient extends DataSourceClient {
    // Marker Interface，无额外方法
}
```

**基础实现**：

```java
public abstract class BaseAdHocDataSourceClient implements AdHocDataSourceClient {
    private final BaseConnectionParam baseConnectionParam;
    private final DbType dbType;
    
    @Override
    public Connection getConnection() throws SQLException {
        // 每次都创建新连接（不池化）
        return DataSourceProcessorProvider
            .getDataSourceProcessor(dbType)
            .getConnection(baseConnectionParam);
    }
    
    @Override
    public void close() {
        // 空实现，不需要关闭资源
    }
}
```

**特点**：
- 🔸 每次调用 `getConnection()` 都创建新连接
- 🔸 不维护连接池
- 🔸 适合偶尔使用的场景（测试连接、临时查询）
- 🔸 资源开销大，但不占用长期资源

---

#### 2.5.3 PooledDataSourceClient - 池化客户端

```java
public interface PooledDataSourceClient extends DataSourceClient {
    DataSource createDataSourcePool(BaseConnectionParam params, DbType dbType);
}
```

**基础实现**：

```java
public abstract class BasePooledDataSourceClient implements PooledDataSourceClient {
    protected final BaseConnectionParam baseConnectionParam;
    protected HikariDataSource dataSource;  // 使用 HikariCP 连接池
    
    public BasePooledDataSourceClient(BaseConnectionParam params, DbType dbType) {
        this.baseConnectionParam = params;
        this.dataSource = createDataSourcePool(params, dbType);
    }
    
    @Override
    public HikariDataSource createDataSourcePool(
            BaseConnectionParam params, DbType dbType) {
        
        HikariDataSource ds = new HikariDataSource();
        
        // 配置连接参数
        ds.setDriverClassName(params.getDriverClassName());
        ds.setJdbcUrl(DataSourceUtils.getJdbcUrl(dbType, params));
        ds.setUsername(params.getUser());
        ds.setPassword(PasswordUtils.decodePassword(params.getPassword()));
        
        // 配置连接池参数
        ds.setMinimumIdle(5);           // 最小空闲连接
        ds.setMaximumPoolSize(50);       // 最大连接数
        ds.setConnectionTestQuery(params.getValidationQuery());
        
        return ds;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        // 从连接池获取连接（复用）
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
- 🔸 维护 HikariCP 连接池
- 🔸 `getConnection()` 从池中获取（复用）
- 🔸 适合高频访问场景
- 🔸 性能高，但占用长期资源

---

### 2.6 两种客户端对比

| 特性 | AdHocDataSourceClient | PooledDataSourceClient |
|-----|----------------------|------------------------|
| **连接方式** | 每次创建新连接 | 从连接池获取 |
| **性能** | 较低（每次建连接开销大） | 高（复用连接） |
| **资源占用** | 无长期占用 | 占用连接池资源 |
| **适用场景** | 偶尔查询、测试连接 | 高频访问、生产环境 |
| **关闭行为** | 空操作 | 关闭连接池 |
| **生命周期** | 短暂 | 长期 |

---

## 三、设计模式分析

### 3.1 SPI 模式（Service Provider Interface）

**定义**：Java 标准的服务提供者机制

**实现方式**：
1. 定义接口：`DataSourceChannelFactory`
2. 实现类标注：`@AutoService(DataSourceChannelFactory.class)`
3. 自动发现：`ServiceLoader.load(DataSourceChannelFactory.class)`

**优势**：
- ✅ 插件无需手动注册
- ✅ 支持运行时动态发现
- ✅ 完全解耦

**代码示例**：

```java
// 1. 定义 SPI 接口
public interface DataSourceChannelFactory extends PrioritySPI {
    DataSourceChannel create();
}

// 2. 实现插件（自动注册）
@AutoService(DataSourceChannelFactory.class)
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    @Override
    public DataSourceChannel create() {
        return new MySQLDataSourceChannel();
    }
}

// 3. 自动发现和加载
PrioritySPIFactory<DataSourceChannelFactory> factory = 
    new PrioritySPIFactory<>(DataSourceChannelFactory.class);
// ServiceLoader 自动扫描 META-INF/services/ 目录
```

---

### 3.2 工厂模式（Factory Pattern）

**应用场景 1：DataSourceChannelFactory 创建 Channel**

```java
public interface DataSourceChannelFactory {
    DataSourceChannel create();  // 工厂方法
}

// MySQL 工厂
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    @Override
    public DataSourceChannel create() {
        return new MySQLDataSourceChannel();
    }
}

// PostgreSQL 工厂
public class PostgreSQLDataSourceChannelFactory implements DataSourceChannelFactory {
    @Override
    public DataSourceChannel create() {
        return new PostgreSQLDataSourceChannel();
    }
}
```

**应用场景 2：DataSourceChannel 创建 Client**

```java
public interface DataSourceChannel {
    AdHocDataSourceClient createAdHocDataSourceClient(...);
    PooledDataSourceClient createPooledDataSourceClient(...);
}

// MySQL Channel
public class MySQLDataSourceChannel implements DataSourceChannel {
    @Override
    public AdHocDataSourceClient createAdHocDataSourceClient(...) {
        return new MySQLAdHocDataSourceClient(...);
    }
    
    @Override
    public PooledDataSourceClient createPooledDataSourceClient(...) {
        return new MySQLPooledDataSourceClient(...);
    }
}
```

**优势**：
- ✅ 封装对象创建逻辑
- ✅ 客户端无需知道具体类名
- ✅ 易于扩展新类型

---

### 3.3 抽象工厂模式（Abstract Factory Pattern）

**定义**：DataSourceChannel 就是一个抽象工厂

```java
public interface DataSourceChannel {
    // 创建产品族 1：AdHoc 客户端
    AdHocDataSourceClient createAdHocDataSourceClient(...);
    
    // 创建产品族 2：Pooled 客户端
    PooledDataSourceClient createPooledDataSourceClient(...);
}
```

**特点**：
- 一个工厂创建**一系列相关产品**
- MySQL Channel 创建 MySQL 的 AdHoc 和 Pooled 客户端
- PostgreSQL Channel 创建 PostgreSQL 的 AdHoc 和 Pooled 客户端

---

### 3.4 策略模式（Strategy Pattern）

**应用场景**：两种客户端策略

```java
// 策略接口
public interface DataSourceClient {
    Connection getConnection();
}

// 策略 1：临时连接策略
public class AdHocDataSourceClient implements DataSourceClient {
    @Override
    public Connection getConnection() {
        return createNewConnection();  // 每次创建新连接
    }
}

// 策略 2：池化连接策略
public class PooledDataSourceClient implements DataSourceClient {
    private HikariDataSource pool;
    
    @Override
    public Connection getConnection() {
        return pool.getConnection();  // 从池中获取
    }
}
```

**使用方式**：

```java
// 根据场景选择策略
DataSourceClient client;
if (highFrequency) {
    client = channel.createPooledDataSourceClient(...);  // 高频用池化
} else {
    client = channel.createAdHocDataSourceClient(...);   // 低频用临时
}

Connection conn = client.getConnection();
```

**优势**：
- ✅ 同一接口，不同实现
- ✅ 运行时切换策略
- ✅ 符合开闭原则

---

### 3.5 单例模式（Singleton Pattern）

**应用场景 1：DataSourcePluginManager**

```java
public class DataSourceClientProvider {
    // 静态单例
    private static final DataSourcePluginManager dataSourcePluginManager 
        = new DataSourcePluginManager();
    
    static {
        dataSourcePluginManager.installPlugin();  // 启动时初始化
    }
}
```

**应用场景 2：连接池缓存**

```java
public class DataSourceClientProvider {
    // Guava Cache 单例
    private static final Cache<String, PooledDataSourceClient> 
        POOLED_DATASOURCE_CLIENT_CACHE = CacheBuilder.newBuilder()
            .maximumSize(100)
            .build();
}
```

**优势**：
- ✅ 全局唯一实例
- ✅ 避免重复初始化
- ✅ 资源共享

---

### 3.6 模板方法模式（Template Method Pattern）

**应用场景**：BaseAdHocDataSourceClient / BasePooledDataSourceClient

```java
public abstract class BasePooledDataSourceClient implements PooledDataSourceClient {
    
    public BasePooledDataSourceClient(BaseConnectionParam params, DbType dbType) {
        // 模板方法：定义算法骨架
        this.baseConnectionParam = params;
        this.dataSource = createDataSourcePool(params, dbType);  // 调用子类实现
    }
    
    // 通用方法
    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    // 钩子方法：子类可以扩展
    protected void configureDataSource(HikariDataSource ds) {
        // 默认实现，子类可覆盖
    }
}

// 子类实现
public class MySQLPooledDataSourceClient extends BasePooledDataSourceClient {
    public MySQLPooledDataSourceClient(BaseConnectionParam params, DbType dbType) {
        super(params, dbType);
    }
    
    // 可以覆盖钩子方法
    @Override
    protected void configureDataSource(HikariDataSource ds) {
        // MySQL 特殊配置
        ds.addDataSourceProperty("cachePrepStmts", "true");
    }
}
```

---

### 3.7 适配器模式（Adapter Pattern）

**应用场景**：DataSourceProcessor 适配不同数据库

```java
public interface DataSourceProcessor {
    Connection getConnection(ConnectionParam params);
    String getJdbcUrl(ConnectionParam params);
    String getValidationQuery();
}

// MySQL 适配器
public class MySQLDataSourceProcessor implements DataSourceProcessor {
    @Override
    public Connection getConnection(ConnectionParam params) {
        // 适配 MySQL 的连接方式
        return DriverManager.getConnection(params.getJdbcUrl(), ...);
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1";  // MySQL 特定
    }
}

// PostgreSQL 适配器
public class PostgreSQLDataSourceProcessor implements DataSourceProcessor {
    @Override
    public String getValidationQuery() {
        return "SELECT 1";  // PostgreSQL 特定
    }
}

// Oracle 适配器
public class OracleDataSourceProcessor implements DataSourceProcessor {
    @Override
    public String getValidationQuery() {
        return "SELECT 1 FROM DUAL";  // Oracle 特定
    }
}
```

---

### 3.8 代理模式（Proxy Pattern）

**应用场景**：连接池本身就是代理

```java
public class PooledDataSourceClient implements DataSourceClient {
    private HikariDataSource pool;  // 代理
    
    @Override
    public Connection getConnection() {
        // 通过代理（连接池）获取连接
        // 连接池内部实现了：
        // 1. 连接复用
        // 2. 连接验证
        // 3. 连接超时处理
        return pool.getConnection();
    }
}
```

---

## 四、完整调用流程

### 4.1 系统启动流程

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 应用启动                                                  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 2. DataSourcePluginManager.installPlugin()                  │
│    → 创建 PrioritySPIFactory                                │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 3. ServiceLoader.load(DataSourceChannelFactory.class)       │
│    → 扫描 META-INF/services/                                │
│    → 发现所有标注 @AutoService 的实现类                      │
└────────────────────────┬────────────────────────────────────┘
                         │
                  ┌──────┴──────┐
                  │ 发现插件：   │
                  ├─────────────┤
                  │ MySQL       │
                  │ PostgreSQL  │
                  │ Oracle      │
                  │ SSH         │
                  │ ... (28种)  │
                  └──────┬──────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 4. 遍历所有 Factory，调用 create()                           │
│    MySQLDataSourceChannelFactory.create()                   │
│      → new MySQLDataSourceChannel()                         │
│    PostgreSQLDataSourceChannelFactory.create()              │
│      → new PostgreSQLDataSourceChannel()                    │
│    ...                                                      │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 5. 保存到 Map<String, DataSourceChannel>                    │
│    datasourceChannelMap.put("mysql", mysqlChannel)          │
│    datasourceChannelMap.put("postgresql", postgresChannel)  │
│    ...                                                      │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 业务使用流程

```
┌─────────────────────────────────────────────────────────────┐
│ 业务代码：需要连接 MySQL 数据库                              │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 调用：DataSourceClientProvider.getPooledConnection(         │
│     DbType.MYSQL, connectionParams)                         │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│ 1. 查询缓存：检查是否已有该数据源的连接池客户端                │
│    cacheKey = getDatasourceUniqueId(params, dbType)         │
└────────────────────────┬────────────────────────────────────┘
                         │
                    ┌────┴────┐
                    │ 缓存命中?│
                    └────┬────┘
               Yes ◄─────┴─────► No
                │                │
      ┌─────────▼─────────┐     │
      │ 返回缓存的客户端    │     │
      └───────────────────┘     │
                                │
                  ┌─────────────▼─────────────┐
                  │ 2. 创建新客户端             │
                  └─────────────┬─────────────┘
                                │
          ┌─────────────────────▼─────────────────────┐
          │ 3. 获取 DataSourceChannel                  │
          │    channel = channelMap.get("mysql")       │
          │    → 返回 MySQLDataSourceChannel           │
          └─────────────────────┬─────────────────────┘
                                │
          ┌─────────────────────▼─────────────────────┐
          │ 4. 创建池化客户端                           │
          │    client = channel.createPooledDataSourceClient│
          │      → new MySQLPooledDataSourceClient()   │
          └─────────────────────┬─────────────────────┘
                                │
          ┌─────────────────────▼─────────────────────┐
          │ 5. 客户端初始化                             │
          │    MySQLPooledDataSourceClient 构造函数：   │
          │    → 调用 createDataSourcePool()           │
          │    → 创建 HikariDataSource                 │
          │    → 配置连接池参数                         │
          └─────────────────────┬─────────────────────┘
                                │
          ┌─────────────────────▼─────────────────────┐
          │ 6. 保存到缓存                               │
          │    cache.put(cacheKey, client)             │
          └─────────────────────┬─────────────────────┘
                                │
                  ┌─────────────▼─────────────┐
                  │ 7. 获取连接                │
                  │    client.getConnection()  │
                  │    → pool.getConnection()  │
                  └─────────────┬─────────────┘
                                │
                  ┌─────────────▼─────────────┐
                  │ 8. 返回 Connection         │
                  │    业务代码使用连接          │
                  └───────────────────────────┘
```

### 4.3 AdHoc 客户端流程

```
业务代码：测试连接
    │
    ├─► DataSourceClientProvider.getAdHocConnection(DbType.MYSQL, params)
    │
    ├─► channel = channelMap.get("mysql")
    │       → MySQLDataSourceChannel
    │
    ├─► client = channel.createAdHocDataSourceClient(params, dbType)
    │       → new MySQLAdHocDataSourceClient()
    │
    ├─► client.getConnection()
    │       → DataSourceProcessor.getConnection()
    │       → DriverManager.getConnection()  ← 每次都创建新连接
    │
    └─► 返回 Connection
```

---

## 五、实战代码示例

### 5.1 使用 Pooled 客户端（高频场景）

```java
public class DataQueryService {
    
    public List<Map<String, Object>> queryData(DbType dbType, String sql) 
            throws Exception {
        
        // 1. 准备连接参数
        BaseConnectionParam params = new MySQLConnectionParam();
        params.setUser("root");
        params.setPassword("123456");
        params.setAddress("localhost:3306");
        params.setDatabase("test_db");
        
        // 2. 获取池化连接（会缓存，复用）
        try (Connection conn = DataSourceClientProvider.getPooledConnection(
                dbType, params)) {
            
            // 3. 执行查询
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    // 处理结果...
                    result.add(extractRow(rs));
                }
                return result;
            }
        }
        // 注意：Connection 关闭后只是归还到池中，不是真正关闭
    }
}
```

**特点**：
- ✅ 第一次调用：创建连接池
- ✅ 后续调用：从缓存获取客户端，从池中获取连接
- ✅ 性能高，适合高频查询

---

### 5.2 使用 AdHoc 客户端（测试场景）

```java
public class DataSourceTestService {
    
    public boolean testConnection(DbType dbType, ConnectionParam params) {
        
        try {
            // 1. 获取临时连接（不缓存，不池化）
            try (Connection conn = DataSourceClientProvider.getAdHocConnection(
                    dbType, params)) {
                
                // 2. 执行测试查询
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeQuery("SELECT 1");
                }
                
                return true;
            }
            
        } catch (Exception e) {
            log.error("连接测试失败", e);
            return false;
        }
        // 注意：Connection 关闭后真正关闭，不会保留
    }
}
```

**特点**：
- ✅ 每次创建新连接
- ✅ 不占用长期资源
- ✅ 适合偶尔使用的场景

---

### 5.3 实现自定义数据源插件

假设要支持新数据源 `TiDB`：

#### 步骤 1：添加 DbType 枚举

```java
public enum DbType {
    // ... 现有的
    TIDB(28, "tidb", "tidb");  // 新增
}
```

#### 步骤 2：创建 ConnectionParam

```java
@Data
public class TiDBConnectionParam extends BaseConnectionParam {
    // TiDB 特有参数
    private String pdAddress;  // PD 地址
}
```

#### 步骤 3：实现 DataSourceChannelFactory

```java
@AutoService(DataSourceChannelFactory.class)  // 自动注册 SPI
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
```

#### 步骤 4：实现 DataSourceChannel

```java
public class TiDBDataSourceChannel implements DataSourceChannel {
    
    @Override
    public AdHocDataSourceClient createAdHocDataSourceClient(
            BaseConnectionParam params, DbType dbType) {
        return new TiDBAdHocDataSourceClient(params, dbType);
    }
    
    @Override
    public PooledDataSourceClient createPooledDataSourceClient(
            BaseConnectionParam params, DbType dbType) {
        return new TiDBPooledDataSourceClient(params, dbType);
    }
}
```

#### 步骤 5：实现 Client

```java
// AdHoc 客户端
public class TiDBAdHocDataSourceClient extends BaseAdHocDataSourceClient {
    public TiDBAdHocDataSourceClient(BaseConnectionParam params, DbType dbType) {
        super(params, dbType);
    }
}

// Pooled 客户端
public class TiDBPooledDataSourceClient extends BasePooledDataSourceClient {
    public TiDBPooledDataSourceClient(BaseConnectionParam params, DbType dbType) {
        super(params, dbType);
    }
    
    // 可以覆盖配置方法
    @Override
    protected void configureDataSource(HikariDataSource ds) {
        // TiDB 特殊配置
        ds.setConnectionTimeout(30000);
        ds.addDataSourceProperty("useSSL", "false");
    }
}
```

#### 步骤 6：实现 DataSourceProcessor（可选）

```java
@AutoService(DataSourceProcessor.class)
public class TiDBDataSourceProcessor extends AbstractDataSourceProcessor {
    
    @Override
    public String getDatasourceDriver() {
        return "com.mysql.cj.jdbc.Driver";  // TiDB 兼容 MySQL 协议
    }
    
    @Override
    public String getValidationQuery() {
        return "SELECT 1";
    }
    
    @Override
    public DbType getDbType() {
        return DbType.TIDB;
    }
    
    // ... 其他方法实现
}
```

**完成！系统会自动发现并加载 TiDB 插件**

---

### 5.4 优先级覆盖示例

假设要提供一个高性能版本的 MySQL 插件：

```java
@AutoService(DataSourceChannelFactory.class)
public class OptimizedMySQLDataSourceChannelFactory 
        implements DataSourceChannelFactory {
    
    @Override
    public String getName() {
        return DbType.MYSQL.getName();  // 同名
    }
    
    @Override
    public SPIIdentify getIdentify() {
        return SPIIdentify.builder()
            .name(getName())
            .priority(10)  // 高优先级
            .build();
    }
    
    @Override
    public DataSourceChannel create() {
        return new OptimizedMySQLDataSourceChannel();  // 优化版本
    }
}
```

**结果**：
- 系统会检测到同名插件
- 比较优先级：10 > 0（默认）
- 使用高优先级插件，忽略默认插件
- 日志输出：`高优先级插件 OptimizedMySQLDataSourceChannelFactory 覆盖 MySQLDataSourceChannelFactory`

---

## 六、设计模式总结表

| 设计模式 | 应用位置 | 作用 |
|---------|---------|------|
| **SPI 模式** | PrioritySPI + ServiceLoader | 插件自动发现和加载 |
| **工厂模式** | DataSourceChannelFactory | 创建 DataSourceChannel |
| **抽象工厂模式** | DataSourceChannel | 创建一系列相关产品（AdHoc + Pooled） |
| **策略模式** | AdHoc / Pooled Client | 不同连接获取策略 |
| **单例模式** | DataSourcePluginManager | 全局唯一的插件管理器 |
| **模板方法模式** | BasePooledDataSourceClient | 定义算法骨架，子类扩展 |
| **适配器模式** | DataSourceProcessor | 适配不同数据库的差异 |
| **代理模式** | HikariDataSource | 连接池代理实际连接 |

---

## 七、核心优势总结

### 7.1 架构优势

✅ **插件化**：新增数据源无需修改核心代码  
✅ **解耦**：SPI 层、通道层、客户端层职责清晰  
✅ **扩展性**：优先级机制支持插件覆盖和增强  
✅ **灵活性**：两种连接策略适配不同场景  
✅ **性能**：连接池 + 缓存机制提升性能  

### 7.2 设计模式优势

✅ **SPI 模式**：自动发现，无需手动注册  
✅ **工厂模式**：封装创建逻辑，易于扩展  
✅ **策略模式**：运行时选择策略  
✅ **单例模式**：避免重复初始化  
✅ **模板方法**：复用通用逻辑  

---

## 八、关键要点

### 8.1 类关系总结

```
PrioritySPI (基础)
    ↓ extends
DataSourceChannelFactory (工厂)
    ↓ create()
DataSourceChannel (通道)
    ↓ createXxxClient()
DataSourceClient (客户端)
    ├─ AdHocDataSourceClient (临时)
    └─ PooledDataSourceClient (池化)
```

### 8.2 生命周期

```
系统启动
    → PrioritySPIFactory 加载所有 Factory
    → 每个 Factory.create() 创建 Channel
    → Channel 注册到 Map
    
业务使用
    → 根据 DbType 获取 Channel
    → Channel 创建对应的 Client
    → Client 获取 Connection
```

### 8.3 性能优化

- 🚀 连接池复用连接
- 🚀 客户端缓存（Guava Cache）
- 🚀 懒加载（按需创建）
- 🚀 资源自动释放（AutoCloseable）

---

希望这份详细的文档能帮助您深入理解 DolphinScheduler 的 SPI 架构和设计模式！🎉

