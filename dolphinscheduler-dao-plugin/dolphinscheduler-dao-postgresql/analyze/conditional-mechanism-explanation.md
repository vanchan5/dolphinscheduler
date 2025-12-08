# Spring @Conditional 机制完整解析

## 📋 概述

本文档详细解释 Spring 的 `@Conditional` 注解和 `Condition` 接口的工作原理，以及它们在 DolphinScheduler DAO 插件化架构中的应用。

---

## 🎯 核心组件

### 1. Condition 接口

**位置**：`org.springframework.context.annotation.Condition`

```java
public interface Condition {
    boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata);
}
```

**作用**：定义条件判断逻辑，决定是否创建 Bean。

### 2. @Conditional 注解

**位置**：`org.springframework.context.annotation.Conditional`

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Conditional {
    Class<? extends Condition>[] value();
}
```

**作用**：标记配置类或方法，只有当指定的 Condition 返回 `true` 时才创建 Bean。

---

## 🔍 DatabaseEnvironmentCondition 工作原理

### 类结构

```java
public class DatabaseEnvironmentCondition implements Condition {
    private final String profile;  // 期望的 Profile 名称

    public DatabaseEnvironmentCondition(String profile) {
        this.profile = profile;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 1. 获取当前激活的 Profile 列表
        String[] activeProfiles = context.getEnvironment().getActiveProfiles();
        
        // 2. 检查期望的 Profile 是否在激活列表中
        return Arrays.asList(activeProfiles).contains(profile);
    }
}
```

### 关键方法解析

#### `matches()` 方法

**参数说明**：

1. **`ConditionContext context`**：
   - 提供 Spring 应用上下文信息
   - 可以访问 `Environment`、`BeanFactory`、`ClassLoader` 等

2. **`AnnotatedTypeMetadata metadata`**：
   - 提供被注解的类或方法的元数据
   - 可以访问注解信息

**执行逻辑**：

```java
// 步骤 1：获取激活的 Profile
String[] activeProfiles = context.getEnvironment().getActiveProfiles();
// 例如：["postgresql"] 或 ["mysql", "dev"]

// 步骤 2：检查期望的 Profile 是否存在
return Arrays.asList(activeProfiles).contains(profile);
// 例如：profile = "postgresql"，activeProfiles 包含 "postgresql" → true
```

---

## 🔄 PostgresqlDatabaseEnvironmentCondition

### 类定义

```java
public class PostgresqlDatabaseEnvironmentCondition extends DatabaseEnvironmentCondition {
    public PostgresqlDatabaseEnvironmentCondition() {
        super("postgresql");  // 传入期望的 Profile 名称
    }
}
```

### 设计模式

**模板方法模式**：
- 基类 `DatabaseEnvironmentCondition` 提供通用逻辑
- 子类只需传入特定的 Profile 名称

**优势**：
- ✅ 代码复用：所有数据库插件共享相同的判断逻辑
- ✅ 简洁明了：每个插件只需一行代码
- ✅ 易于维护：修改判断逻辑只需修改基类

---

## 🎨 @Conditional 工作流程

### 完整流程图

```
┌─────────────────────────────────────────────────────────────┐
│  步骤 1: Spring Boot 启动                                  │
│  SpringApplication.run()                                    │
│    ↓                                                        │
│  扫描所有 @Configuration 类                                 │
└────────────────────┬───────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 2: 发现 PostgresqlDaoPluginAutoConfiguration         │
│  @Conditional(PostgresqlDatabaseEnvironmentCondition.class) │
│  @Configuration                                              │
│  public class PostgresqlDaoPluginAutoConfiguration { }      │
└────────────────────┬───────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 3: 检查 @Conditional 注解                             │
│  Spring 发现 @Conditional 注解                              │
│    ↓                                                        │
│  获取 Condition 类：PostgresqlDatabaseEnvironmentCondition │
│    ↓                                                        │
│  实例化 Condition 对象                                      │
│    new PostgresqlDatabaseEnvironmentCondition()            │
│    → 调用 super("postgresql")                              │
└────────────────────┬───────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 4: 调用 matches() 方法                                │
│  condition.matches(context, metadata)                      │
│    ↓                                                        │
│  1. 获取激活的 Profile                                       │
│     String[] activeProfiles = context.getEnvironment()     │
│                            .getActiveProfiles();            │
│     // 例如：["postgresql"]                                │
│    ↓                                                        │
│  2. 检查 Profile 是否匹配                                   │
│     Arrays.asList(activeProfiles).contains("postgresql")   │
│     // true                                                │
└────────────────────┬───────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 5: 根据结果决定是否创建 Bean                           │
│  if (matches() == true) {                                  │
│    ✅ 创建 PostgresqlDaoPluginAutoConfiguration Bean        │
│    ✅ 执行 @Bean 方法                                       │
│  } else {                                                   │
│    ❌ 忽略这个配置类                                         │
│    ❌ 不创建任何 Bean                                        │
│  }                                                          │
└────────────────────┬───────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 6: 注入到 DaoConfiguration                           │
│  @Autowired                                                  │
│  DaoPluginConfiguration daoPluginConfiguration;             │
│    ↓                                                        │
│  Spring 自动注入 PostgresqlDaoPluginAutoConfiguration       │
│  (因为它是唯一匹配的 DaoPluginConfiguration 实现)           │
└─────────────────────────────────────────────────────────────┘
```

---

## 📊 PostgresqlDaoPluginAutoConfiguration 完整流程

### 类定义

```java
@Conditional(PostgresqlDatabaseEnvironmentCondition.class)  // ← 条件注解
@Configuration(proxyBeanMethods = false)                    // ← 配置类
public class PostgresqlDaoPluginAutoConfiguration implements DaoPluginConfiguration {

    @Autowired
    private DataSource dataSource;  // ← 注入 DataSource

    @Override
    public DbType dbType() {
        return DbType.POSTGRE_SQL;
    }

    @Override
    public DatabaseMonitor databaseMonitor() {
        return new PostgresqlMonitor(dataSource);
    }

    @Override
    public DatabaseDialect databaseDialect() {
        return new PostgresqlDialect(dataSource);
    }
}
```

### 执行步骤详解

#### 步骤 1：条件检查

```java
@Conditional(PostgresqlDatabaseEnvironmentCondition.class)
```

**执行过程**：
1. Spring 发现 `@Conditional` 注解
2. 获取 `PostgresqlDatabaseEnvironmentCondition.class`
3. 实例化 Condition 对象
4. 调用 `matches()` 方法
5. 检查 `spring.profiles.active` 是否包含 `"postgresql"`

**结果**：
- 如果 `spring.profiles.active = postgresql` → `matches() = true` → 继续
- 如果 `spring.profiles.active = mysql` → `matches() = false` → 跳过

#### 步骤 2：创建配置类实例

```java
@Configuration(proxyBeanMethods = false)
```

**执行过程**：
1. 如果条件满足，Spring 创建 `PostgresqlDaoPluginAutoConfiguration` 实例
2. 处理 `@Autowired` 注解，注入 `DataSource`
3. 将实例注册为 Spring Bean

#### 步骤 3：实现接口方法

```java
@Override
public DbType dbType() {
    return DbType.POSTGRE_SQL;
}

@Override
public DatabaseMonitor databaseMonitor() {
    return new PostgresqlMonitor(dataSource);
}

@Override
public DatabaseDialect databaseDialect() {
    return new PostgresqlDialect(dataSource);
}
```

**说明**：
- 这些方法实现了 `DaoPluginConfiguration` 接口
- 当 `DaoConfiguration` 调用这些方法时，会返回 PostgreSQL 特定的实现

---

## 🔧 @Configuration(proxyBeanMethods = false) 详解

### 默认行为（proxyBeanMethods = true）

```java
@Configuration  // 默认 proxyBeanMethods = true
public class MyConfiguration {
    
    @Bean
    public ServiceA serviceA() {
        return new ServiceA();
    }
    
    @Bean
    public ServiceB serviceB() {
        return new ServiceB(serviceA());  // ← 调用方法
    }
}
```

**工作原理**：
1. Spring 使用 **CGLIB 代理**创建配置类
2. 当调用 `serviceA()` 时，代理会：
   - 检查 Bean 是否已存在
   - 如果存在，返回已创建的 Bean（单例）
   - 如果不存在，创建新 Bean

**优势**：
- ✅ 保证 Bean 的单例性
- ✅ 方法间调用会返回同一个 Bean 实例

**缺点**：
- ❌ 需要 CGLIB 代理，性能开销
- ❌ 配置类不能被 `final` 修饰
- ❌ 启动时间稍长

### proxyBeanMethods = false

```java
@Configuration(proxyBeanMethods = false)  // ← 禁用代理
public class PostgresqlDaoPluginAutoConfiguration {
    // ...
}
```

**工作原理**：
1. Spring **不使用代理**，直接创建配置类实例
2. 方法调用是**普通方法调用**，不是代理调用

**优势**：
- ✅ **性能更好**：无需 CGLIB 代理
- ✅ **启动更快**：减少代理创建时间
- ✅ **内存更少**：不需要代理对象

**适用场景**：
- ✅ 配置类中的 `@Bean` 方法**不相互调用**
- ✅ 配置类只实现接口，不定义 `@Bean` 方法
- ✅ 性能敏感的场景

### 在 PostgresqlDaoPluginAutoConfiguration 中

```java
@Configuration(proxyBeanMethods = false)
public class PostgresqlDaoPluginAutoConfiguration implements DaoPluginConfiguration {
    
    // 没有 @Bean 方法
    // 只实现接口方法
    // 不需要代理
    
    @Override
    public DbType dbType() {
        return DbType.POSTGRE_SQL;  // 直接返回，不需要代理
    }
}
```

**为什么使用 `proxyBeanMethods = false`**：
1. ✅ 这个配置类**没有 `@Bean` 方法**
2. ✅ 方法之间**不相互调用**
3. ✅ 只是实现接口，提供配置信息
4. ✅ 使用代理没有意义，反而增加开销

---

## 🔄 完整工作流程示例

### 场景：激活 PostgreSQL Profile

#### 1. 配置文件

```yaml
spring:
  profiles:
    active: postgresql  # ← 激活 postgresql Profile
```

#### 2. Spring Boot 启动

```
Spring Boot 启动
    ↓
扫描 spring.factories
    ↓
发现所有数据库插件配置类：
  - MysqlDaoPluginAutoConfiguration
  - PostgresqlDaoPluginAutoConfiguration  ← 待检查
  - H2DaoPluginAutoConfiguration
```

#### 3. 条件检查

```
检查 PostgresqlDaoPluginAutoConfiguration
    ↓
@Conditional(PostgresqlDatabaseEnvironmentCondition.class)
    ↓
实例化 PostgresqlDatabaseEnvironmentCondition
    → new PostgresqlDatabaseEnvironmentCondition()
    → super("postgresql")
    ↓
调用 matches() 方法
    ↓
获取激活的 Profile: ["postgresql"]
    ↓
检查: Arrays.asList(["postgresql"]).contains("postgresql")
    ↓
结果: true ✅
```

#### 4. 创建配置类

```
条件满足，创建 PostgresqlDaoPluginAutoConfiguration 实例
    ↓
@Configuration(proxyBeanMethods = false)
    → 不使用代理，直接创建实例
    ↓
@Autowired DataSource dataSource
    → 注入 DataSource Bean
    ↓
注册为 Spring Bean
```

#### 5. 注入到 DaoConfiguration

```
DaoConfiguration {
    @Autowired
    DaoPluginConfiguration daoPluginConfiguration;
    // ↑ Spring 自动注入 PostgresqlDaoPluginAutoConfiguration
    //   因为它是唯一匹配的实现
}
```

#### 6. 使用配置

```
调用 daoPluginConfiguration.dbType()
    → 返回 DbType.POSTGRE_SQL

调用 daoPluginConfiguration.databaseMonitor()
    → 返回 new PostgresqlMonitor(dataSource)

调用 daoPluginConfiguration.databaseDialect()
    → 返回 new PostgresqlDialect(dataSource)
```

---

## 📊 对比其他插件

### MySQL 插件

```java
@Conditional(MysqlDatabaseEnvironmentCondition.class)
@Configuration(proxyBeanMethods = false)
public class MysqlDaoPluginAutoConfiguration implements DaoPluginConfiguration {
    // ...
}
```

**条件检查**：
- `spring.profiles.active = mysql` → ✅ 激活
- `spring.profiles.active = postgresql` → ❌ 忽略

### H2 插件

```java
@Conditional(H2DatabaseEnvironmentCondition.class)
@Configuration(proxyBeanMethods = false)
public class H2DaoPluginAutoConfiguration implements DaoPluginConfiguration {
    // ...
}
```

**条件检查**：
- `spring.profiles.active = h2` → ✅ 激活
- `spring.profiles.active = postgresql` → ❌ 忽略

---

## 💡 关键要点总结

### 1. Condition 接口

- **作用**：定义条件判断逻辑
- **核心方法**：`matches()` - 返回 `true` 则创建 Bean
- **参数**：`ConditionContext`（上下文）、`AnnotatedTypeMetadata`（元数据）

### 2. @Conditional 注解

- **作用**：标记配置类，根据 Condition 决定是否创建
- **工作时机**：在 Bean 创建之前检查
- **结果**：条件满足 → 创建 Bean，条件不满足 → 忽略

### 3. DatabaseEnvironmentCondition

- **设计模式**：模板方法模式
- **判断逻辑**：检查 Profile 是否匹配
- **可复用性**：所有数据库插件共享基类

### 4. proxyBeanMethods = false

- **作用**：禁用 CGLIB 代理
- **优势**：性能更好、启动更快
- **适用**：配置类没有 `@Bean` 方法相互调用

### 5. 完整流程

```
Profile 激活 → Condition 检查 → 创建配置类 → 注入使用
```

---

## 🔗 相关文件

- `DatabaseEnvironmentCondition.java` - 条件判断基类
- `PostgresqlDatabaseEnvironmentCondition.java` - PostgreSQL 条件类
- `PostgresqlDaoPluginAutoConfiguration.java` - PostgreSQL 配置类
- `DaoConfiguration.java` - 核心配置类

---

*最后更新：2024*

