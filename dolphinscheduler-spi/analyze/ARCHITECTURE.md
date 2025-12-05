# DolphinScheduler SPI & DataSource API 架构图

## 📋 文件说明

本目录包含 4 个 PlantUML 架构图，全面展示 dolphinscheduler-spi 和 dolphinscheduler-datasource-api 的工作原理。

### 文件列表

| 文件名 | 说明 | 内容 |
|-------|------|-----|
| `spi-datasource-architecture.puml` | 完整架构图 | 类关系、设计模式、核心特性 |
| `spi-datasource-workflow.puml` | 系统启动流程 | SPI 插件加载过程 |
| `spi-datasource-pooled-usage.puml` | 池化连接流程 | Pooled 连接使用时序图 |
| `spi-datasource-adhoc-usage.puml` | 临时连接流程 | AdHoc 连接使用时序图 |

---

## 🚀 快速查看

### 方法 1：在线预览（推荐）

访问 PlantUML 在线服务器：
```
http://www.plantuml.com/plantuml/uml/
```

将 `.puml` 文件内容复制粘贴到编辑器中即可查看。

### 方法 2：VS Code 插件

1. 安装插件：`PlantUML`
2. 打开 `.puml` 文件
3. 按 `Alt + D` 预览

### 方法 3：命令行生成图片

```bash
# macOS 安装 PlantUML
brew install plantuml

# Ubuntu/Debian 安装
sudo apt-get install plantuml

# 生成 PNG 图片
plantuml spi-datasource-architecture.puml

# 生成 SVG 图片（矢量图，推荐）
plantuml -tsvg spi-datasource-architecture.puml

# 批量生成所有图片
plantuml *.puml
```

---

## 📊 架构图详解

### 1. 完整架构图 (`spi-datasource-architecture.puml`)

#### 包含内容
- ✅ dolphinscheduler-spi 所有核心类
- ✅ dolphinscheduler-datasource-api 所有核心类
- ✅ 类之间的关系（继承、实现、依赖）
- ✅ MySQL 插件实现示例
- ✅ 8 种设计模式说明
- ✅ 完整工作流程图
- ✅ 核心特性说明

#### 关键模块

**dolphinscheduler-spi:**
- `plugin` 包：SPI 基础机制
  - `PrioritySPI`：SPI 基础接口
  - `PrioritySPIFactory`：SPI 加载器
  - `SPIIdentify`：插件标识

- `datasource` 包：数据源 SPI 定义
  - `DataSourceChannelFactory`：工厂接口（SPI 扩展点）
  - `DataSourceChannel`：通道接口
  - `DataSourceClient`：客户端接口
  - `AdHocDataSourceClient`：临时客户端接口
  - `PooledDataSourceClient`：池化客户端接口
  - `BaseConnectionParam`：连接参数基类

- `params` 包：参数系统
  - `PluginParams`：参数模型
  - `PluginParamsTransfer`：参数转换工具

- `enums` 包：枚举定义
  - `DbType`：28 种数据源类型

**dolphinscheduler-datasource-api:**
- `plugin` 包：插件管理
  - `DataSourcePluginManager`：数据源插件管理器
  - `DataSourceClientProvider`：客户端提供者（门面）
  - `DataSourceProcessorManager`：处理器管理器
  - `DataSourceProcessorProvider`：处理器提供者

- `client` 包：客户端实现
  - `BaseAdHocDataSourceClient`：临时客户端基类
  - `BasePooledDataSourceClient`：池化客户端基类

- `datasource` 包：数据源处理器
  - `DataSourceProcessor`：处理器接口
  - `AbstractDataSourceProcessor`：抽象处理器
  - `BaseDataSourceParamDTO`：参数 DTO

- `utils` 包：工具类
  - `DataSourceUtils`：数据源工具
  - `PasswordUtils`：密码工具

---

### 2. 系统启动流程 (`spi-datasource-workflow.puml`)

展示系统启动时如何加载和初始化所有数据源插件。

#### 流程步骤

1. **应用启动**
   ```
   DataSourcePluginManager.installPlugin()
   ```

2. **创建 SPI 工厂**
   ```java
   PrioritySPIFactory<DataSourceChannelFactory> factory = 
       new PrioritySPIFactory<>(DataSourceChannelFactory.class);
   ```

3. **ServiceLoader 自动发现**
   - 扫描 `META-INF/services/org.apache.dolphinscheduler.spi.datasource.DataSourceChannelFactory`
   - 发现所有标注 `@AutoService` 的实现类
   - 返回所有插件实例

4. **处理优先级冲突**
   - 同名同优先级：抛出异常
   - 同名不同优先级：保留高优先级插件

5. **创建 DataSourceChannel**
   ```java
   for (DataSourceChannelFactory factory : factories) {
       DataSourceChannel channel = factory.create();
       datasourceChannelMap.put(factory.getName(), channel);
   }
   ```

6. **插件注册完成**
   - 维护 Map: `{"mysql": MySQLChannel, "postgresql": PGChannel, ...}`

---

### 3. 池化连接流程 (`spi-datasource-pooled-usage.puml`)

展示业务代码如何使用池化连接（高频场景）。

#### 使用示例

```java
// 业务代码
try (Connection conn = DataSourceClientProvider.getPooledConnection(
        DbType.MYSQL, connectionParams)) {
    
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    // 处理结果...
}
```

#### 流程步骤

1. **检查缓存**
   ```java
   String key = getDatasourceUniqueId(params, dbType);
   Client cachedClient = POOLED_DATASOURCE_CLIENT_CACHE.get(key);
   ```

2. **缓存未命中，创建新客户端**
   ```java
   DataSourceChannel channel = datasourceChannelMap.get("mysql");
   MySQLPooledDataSourceClient client = 
       channel.createPooledDataSourceClient(params, dbType);
   ```

3. **初始化连接池**
   ```java
   HikariDataSource pool = new HikariDataSource();
   pool.setDriverClassName("com.mysql.cj.jdbc.Driver");
   pool.setJdbcUrl("jdbc:mysql://localhost:3306/db");
   pool.setUsername("root");
   pool.setPassword("******");
   pool.setMinimumIdle(5);
   pool.setMaximumPoolSize(50);
   ```

4. **保存到缓存**
   ```java
   cache.put(key, client);
   ```

5. **获取连接**
   ```java
   Connection conn = client.getConnection();
   // 实际调用：pool.getConnection()
   // 从连接池获取，复用现有连接
   ```

6. **关闭连接**
   ```java
   conn.close();
   // 连接归还到池中，不是真正关闭
   ```

#### 特点
- ✅ 首次调用：创建连接池
- ✅ 后续调用：从缓存获取客户端，从池中获取连接
- ✅ 性能高，适合高频访问

---

### 4. 临时连接流程 (`spi-datasource-adhoc-usage.puml`)

展示业务代码如何使用临时连接（测试场景）。

#### 使用示例

```java
// 测试连接
public boolean testConnection(ConnectionParam params) {
    try (Connection conn = DataSourceClientProvider.getAdHocConnection(
            DbType.MYSQL, params)) {
        
        Statement stmt = conn.createStatement();
        stmt.executeQuery("SELECT 1");
        return true;  // 连接成功
        
    } catch (Exception e) {
        return false;  // 连接失败
    }
}
```

#### 流程步骤

1. **不使用缓存**
   ```java
   // 每次都创建新客户端
   ```

2. **创建临时客户端**
   ```java
   DataSourceChannel channel = datasourceChannelMap.get("mysql");
   MySQLAdHocDataSourceClient client = 
       channel.createAdHocDataSourceClient(params, dbType);
   ```

3. **获取连接**
   ```java
   Connection conn = client.getConnection();
   // 实际调用：
   // DataSourceProcessor.getConnection(params)
   // DriverManager.getConnection(jdbcUrl, user, password)
   // 每次都创建新连接
   ```

4. **关闭连接**
   ```java
   conn.close();
   // 连接真正关闭，释放数据库资源
   ```

#### 特点
- ✅ 每次创建新连接
- ✅ 不占用长期资源
- ✅ 适合测试、低频场景

---

## 🎨 设计模式应用

架构图中标注了 8 种设计模式的应用：

### 1. SPI 模式
```java
// 定义接口
public interface DataSourceChannelFactory extends PrioritySPI {
    DataSourceChannel create();
}

// 实现插件
@AutoService(DataSourceChannelFactory.class)
public class MySQLDataSourceChannelFactory implements DataSourceChannelFactory {
    // ...
}

// 自动发现
ServiceLoader.load(DataSourceChannelFactory.class)
```

### 2. 工厂模式
```java
// DataSourceChannelFactory 创建 DataSourceChannel
DataSourceChannel channel = factory.create();

// DataSourceChannel 创建 DataSourceClient
DataSourceClient client = channel.createPooledDataSourceClient(params, dbType);
```

### 3. 抽象工厂模式
```java
// DataSourceChannel 创建一系列相关产品
public interface DataSourceChannel {
    AdHocDataSourceClient createAdHocDataSourceClient(...);
    PooledDataSourceClient createPooledDataSourceClient(...);
}
```

### 4. 策略模式
```java
// 两种连接获取策略
DataSourceClient client;
if (highFrequency) {
    client = channel.createPooledDataSourceClient(...);  // 池化策略
} else {
    client = channel.createAdHocDataSourceClient(...);   // 临时策略
}
```

### 5. 单例模式
```java
// 全局唯一的管理器
private static final DataSourcePluginManager dataSourcePluginManager = 
    new DataSourcePluginManager();

// 全局缓存
private static final Cache<String, PooledDataSourceClient> 
    POOLED_DATASOURCE_CLIENT_CACHE = CacheBuilder.newBuilder().build();
```

### 6. 模板方法模式
```java
public abstract class BasePooledDataSourceClient {
    public BasePooledDataSourceClient(params, dbType) {
        this.dataSource = createDataSourcePool(params, dbType);  // 模板方法
    }
    
    // 子类可以覆盖的钩子方法
    protected void configureDataSource(HikariDataSource ds) {
        // 默认实现
    }
}
```

### 7. 适配器模式
```java
// 适配不同数据库的差异
public interface DataSourceProcessor {
    String getValidationQuery();
    Connection getConnection(ConnectionParam params);
}

// MySQL 适配器
public class MySQLDataSourceProcessor implements DataSourceProcessor {
    public String getValidationQuery() { return "SELECT 1"; }
}

// Oracle 适配器
public class OracleDataSourceProcessor implements DataSourceProcessor {
    public String getValidationQuery() { return "SELECT 1 FROM DUAL"; }
}
```

### 8. 门面模式
```java
// DataSourceClientProvider 提供简化的 API
public class DataSourceClientProvider {
    public static Connection getPooledConnection(DbType dbType, ConnectionParam params) {
        // 内部处理复杂的获取逻辑
    }
}
```

---

## 🔑 核心特性

### 插件化
- 基于 Java SPI 机制
- 新增数据源无需修改核心代码
- 通过 `@AutoService` 自动注册

### 优先级机制
- 支持同名插件覆盖
- 高优先级自动替换低优先级
- 冲突检测和日志记录

### 双策略连接
- **AdHoc**：临时连接，即用即销毁
- **Pooled**：连接池化，复用连接

### 连接池管理
- 使用 HikariCP（业界最快的连接池）
- 自动配置最优参数
- 支持缓存复用

### 统一抽象
- 数据源访问统一接口
- 参数配置统一模型
- 适配不同数据库差异

---

## 📝 使用建议

### 在线查看
- **PlantUML Server**: http://www.plantuml.com/plantuml/uml/
- **PlantText**: https://www.planttext.com/

### 本地工具
- **VS Code 插件**: PlantUML
- **IntelliJ IDEA 插件**: PlantUML integration
- **命令行工具**: `plantuml`

### 图片格式选择
- **PNG**: 适合文档嵌入
- **SVG**: 矢量图，适合网页展示
- **PDF**: 适合打印

---

## 🎯 架构优势

### 可扩展性
- ✅ 新增数据源只需实现接口
- ✅ 无需修改核心代码
- ✅ 插件独立开发、部署

### 可维护性
- ✅ 职责清晰，分层明确
- ✅ 接口统一，易于理解
- ✅ 设计模式应用合理

### 性能优化
- ✅ 连接池复用
- ✅ 客户端缓存
- ✅ 懒加载机制

### 灵活性
- ✅ 支持优先级覆盖
- ✅ 双策略适配不同场景
- ✅ 参数系统灵活配置

---

## 📚 参考资料

- [Java SPI 机制](https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html)
- [HikariCP 连接池](https://github.com/brettwooldridge/HikariCP)
- [PlantUML 官方文档](https://plantuml.com/)
- [Google AutoService](https://github.com/google/auto/tree/master/service)

---

## 🤝 贡献

如果您发现架构图有任何问题或建议，欢迎提出 Issue 或 Pull Request。

---

**最后更新**: 2025-12-04

