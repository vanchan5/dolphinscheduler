# DataSource SPI 四类关系的详细图解

## 一、类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                    PrioritySPI (接口)                        │
│  - getIdentify(): SPIIdentify                               │
│  - compareTo(Integer): int                                  │
│                                                              │
│  【作用】SPI 插件的基础接口，所有 SPI 插件都必须实现它        │
│  【职责】提供插件标识和优先级比较能力                        │
└──────────────────┬──────────────────────────────────────────┘
                   │ extends
                   │
┌──────────────────▼──────────────────────────────────────────┐
│            DataSourceChannelFactory (接口)                   │
│  - getName(): String                                         │
│  - create(): DataSourceChannel                               │
│  - getIdentify(): SPIIdentify (继承自 PrioritySPI)          │
│                                                              │
│  【作用】数据源工厂的 SPI 接口                                │
│  【职责】创建 DataSourceChannel 实例                         │
└──────────────────┬──────────────────────────────────────────┘
                   │ implements (实际实现)
                   │
        ┌──────────┼──────────┬──────────┐
        │          │          │          │
┌───────▼──────┐ ┌─▼─────┐ ┌─▼──────┐ ┌─▼──────┐
│ MySQLData... │ │Postgre│ │ SSH... │ │  ...   │
│ SourceCh...  │ │SQL... │ │ ...    │ │ 更多   │
│ Factory      │ │Factory│ │Factory │ │插件    │
└───────┬──────┘ └───┬───┘ └───┬────┘ └────────┘
        │            │          │
        │ create()   │ create() │ create()
        └────────────┼──────────┘
                     │
         ┌───────────▼───────────┐
         │  DataSourceChannel    │
         │  (接口)               │
         │  - createAdHoc...()   │
         │  - createPooled...()  │
         └───────────┬───────────┘
                     │ implements
        ┌────────────┼────────────┐
        │            │            │
┌───────▼──────┐ ┌───▼──────┐ ┌──▼──────┐
│ MySQLData... │ │PostgreSQL│ │  SSH    │
│ SourceCh...  │ │ Data...  │ │ Data... │
└──────────────┘ └──────────┘ └─────────┘

┌─────────────────────────────────────────────────────────────┐
│          PrioritySPIFactory (工具类)                         │
│  - 通过 ServiceLoader 自动发现所有 PrioritySPI 实现          │
│  - 处理同名插件的优先级冲突                                   │
│  - 返回 Map<String, T> (name -> SPI实例)                     │
│                                                              │
│  【作用】SPI 插件的加载和管理工厂                              │
│  【职责】自动发现、加载、冲突解决                              │
└─────────────────────────────────────────────────────────────┘
```

## 二、完整的调用流程

### 步骤 1：系统启动时，加载所有插件

```java
// DataSourcePluginManager.installPlugin()

// 1. 创建 PrioritySPIFactory，传入 DataSourceChannelFactory.class
PrioritySPIFactory<DataSourceChannelFactory> prioritySPIFactory =
        new PrioritySPIFactory<>(DataSourceChannelFactory.class);
//    ↓
// 2. PrioritySPIFactory 内部通过 ServiceLoader 扫描所有实现
ServiceLoader.load(DataSourceChannelFactory.class)
//    ↓
// 3. 自动发现所有标注了 @AutoService 的实现类：
//    - MySQLDataSourceChannelFactory
//    - PostgreSQLDataSourceChannelFactory
//    - SSHDataSourceChannelFactory
//    - ... (28种数据源)
```

### 步骤 2：遍历所有工厂，创建 DataSourceChannel

```java
// 4. 遍历所有发现的 Factory
for (Map.Entry<String, DataSourceChannelFactory> entry : 
     prioritySPIFactory.getSPIMap().entrySet()) {
    
    DataSourceChannelFactory factory = entry.getValue(); // 例如：MySQLDataSourceChannelFactory
    String name = entry.getKey();                        // 例如："mysql"
    
    // 5. 调用 Factory 的 create() 方法
    DataSourceChannel channel = factory.create();
    //    ↓
    //    实际调用 MySQLDataSourceChannelFactory.create()
    //    返回 new MySQLDataSourceChannel()
    
    // 6. 保存到 Map 中
    datasourceChannelMap.put(name, channel);
}
```

### 步骤 3：使用时获取对应的 Channel

```java
// 业务代码中使用
DataSourceChannel mysqlChannel = datasourceChannelMap.get("mysql");
mysqlChannel.createPooledDataSourceClient(params, DbType.MYSQL);
```

## 三、四类详细职责说明

### 1. PrioritySPI（最底层基础接口）

**角色定位**：SPI 插件的"身份证"

```java
public interface PrioritySPI extends Comparable<Integer> {
    SPIIdentify getIdentify();  // 获取插件标识（名称+优先级）
}
```

**关键点**：
- ✅ 所有插件都必须实现这个接口
- ✅ 提供 `getIdentify()` 方法返回插件标识
- ✅ 实现 `Comparable` 接口，支持优先级比较

**类比**：像"身份证"，每个插件都要有身份标识

---

### 2. PrioritySPIFactory（SPI 加载器）

**角色定位**：插件的"发现者和管理者"

```java
public class PrioritySPIFactory<T extends PrioritySPI> {
    private final Map<String, T> map = new HashMap<>();
    
    // 构造函数：自动加载所有 SPI 实现
    public PrioritySPIFactory(Class<T> spiClass) {
        for (T t : ServiceLoader.load(spiClass)) {  // 自动发现
            if (map.containsKey(t.getIdentify().getName())) {
                resolveConflict(t);  // 解决冲突
            } else {
                map.put(t.getIdentify().getName(), t);
            }
        }
    }
}
```

**关键点**：
- ✅ 使用 Java `ServiceLoader` 机制自动发现插件
- ✅ 通过 `META-INF/services/` 目录查找实现类
- ✅ 智能处理同名插件的优先级冲突
- ✅ 返回不可变的 Map（线程安全）

**类比**：像"包工头"，负责找到所有工人，并管理他们

---

### 3. DataSourceChannelFactory（数据源工厂接口）

**角色定位**：数据源插件的"入口点"

```java
public interface DataSourceChannelFactory extends PrioritySPI {
    String getName();                          // 返回数据源名称，如 "mysql"
    DataSourceChannel create();                // 创建对应的 Channel
    SPIIdentify getIdentify();                 // 继承自 PrioritySPI
}
```

**关键点**：
- ✅ 继承 `PrioritySPI`，具备 SPI 能力
- ✅ 提供 `create()` 方法，负责创建 `DataSourceChannel`
- ✅ `getName()` 返回数据源类型标识（如 "mysql", "postgresql"）

**实际实现示例**：

```java
@AutoService(DataSourceChannelFactory.class)  // 自动注册 SPI
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    
    @Override
    public String getName() {
        return DbType.MYSQL.getName();  // 返回 "mysql"
    }
    
    @Override
    public DataSourceChannel create() {
        return new MySQLDataSourceChannel();  // 创建 MySQL 专用的 Channel
    }
    
    // getIdentify() 使用默认实现，基于 getName()
}
```

**类比**：像"工厂经理"，每个数据源都有自己的经理，经理负责创建对应的工作团队

---

### 4. DataSourceChannel（数据源通道接口）

**角色定位**：实际执行数据源操作的"工作者"

```java
public interface DataSourceChannel {
    // 创建临时连接客户端（不池化）
    AdHocDataSourceClient createAdHocDataSourceClient(
        BaseConnectionParam param, DbType dbType);
    
    // 创建池化连接客户端（连接池）
    PooledDataSourceClient createPooledDataSourceClient(
        BaseConnectionParam param, DbType dbType);
}
```

**关键点**：
- ✅ 定义了创建数据源客户端的两种方式
- ✅ `AdHoc`：临时连接，用完即销毁
- ✅ `Pooled`：连接池，复用连接提升性能

**实际实现示例**：

```java
public class MySQLDataSourceChannel implements DataSourceChannel {
    
    @Override
    public AdHocDataSourceClient createAdHocDataSourceClient(
            BaseConnectionParam param, DbType dbType) {
        return new MySQLAdHocDataSourceClient(param, dbType);
    }
    
    @Override
    public PooledDataSourceClient createPooledDataSourceClient(
            BaseConnectionParam param, DbType dbType) {
        return new MySQLPooledDataSourceClient(param, dbType);
    }
}
```

**类比**：像"工作团队"，负责具体的数据库连接操作

---

## 四、完整示例流程

假设系统要连接 MySQL 数据库：

```java
// ============ 第一步：系统启动，加载所有插件 ============
DataSourcePluginManager manager = new DataSourcePluginManager();
manager.installPlugin();

// installPlugin() 内部执行：
PrioritySPIFactory<DataSourceChannelFactory> factory = 
    new PrioritySPIFactory<>(DataSourceChannelFactory.class);
// ↓ ServiceLoader 自动发现：
//   - MySQLDataSourceChannelFactory (name="mysql")
//   - PostgreSQLDataSourceChannelFactory (name="postgresql")
//   - ... 等 28 种

// ↓ 遍历所有 Factory，创建 Channel：
for (DataSourceChannelFactory f : factory.getSPIMap().values()) {
    DataSourceChannel channel = f.create();
    // MySQLDataSourceChannelFactory.create() 
    //   → new MySQLDataSourceChannel()
    manager.datasourceChannelMap.put(f.getName(), channel);
}

// ============ 第二步：业务代码使用 ============
// 获取 MySQL 的 Channel
DataSourceChannel mysqlChannel = 
    manager.datasourceChannelMap.get("mysql");
    // 实际是 MySQLDataSourceChannel 实例

// 创建连接池客户端
MySQLConnectionParam params = new MySQLConnectionParam();
params.setUser("root");
params.setPassword("123456");
params.setAddress("localhost:3306");

PooledDataSourceClient client = 
    mysqlChannel.createPooledDataSourceClient(params, DbType.MYSQL);
    // 实际返回 MySQLPooledDataSourceClient

// 获取数据库连接
Connection conn = client.getConnection();
```

---

## 五、为什么要这样设计？

### 1. **分层解耦**
```
PrioritySPI          ← SPI 机制的基础抽象
    ↓
DataSourceChannelFactory  ← 数据源工厂的抽象
    ↓
DataSourceChannel          ← 数据源操作的抽象
```

每一层职责清晰，互不干扰。

### 2. **插件化扩展**
- 新增数据源：只需实现 `DataSourceChannelFactory` 和 `DataSourceChannel`
- 系统自动发现：通过 `ServiceLoader` 机制
- 无需修改核心代码：完全符合开闭原则

### 3. **优先级管理**
- 支持同名插件覆盖
- 高优先级插件自动替换低优先级
- 冲突检测和日志记录

### 4. **灵活的连接策略**
- `AdHoc`：适合偶尔查询，不占用连接池资源
- `Pooled`：适合高频访问，提升性能

---

## 六、总结关系表

| 类名 | 角色 | 职责 | 类比 |
|-----|------|------|------|
| **PrioritySPI** | SPI 基础接口 | 定义插件标识和优先级 | 身份证 |
| **PrioritySPIFactory** | SPI 加载器 | 自动发现、加载、管理插件 | 包工头 |
| **DataSourceChannelFactory** | 数据源工厂 | 创建 DataSourceChannel | 工厂经理 |
| **DataSourceChannel** | 数据源通道 | 创建实际的连接客户端 | 工作团队 |

**调用链**：
```
PrioritySPIFactory.load() 
    → 发现所有 DataSourceChannelFactory
    → Factory.create() 
    → 返回 DataSourceChannel
    → Channel.createPooledDataSourceClient()
    → 返回实际的客户端
```

希望这个解释能帮助您理解它们的关系！

