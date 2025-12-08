# spring.profiles.active 与 spring.config.activate.on-profile 详解

## 📋 概述

`spring.profiles.active` 和 `spring.config.activate.on-profile` 是 Spring Boot 中两个相关但用途不同的配置项，都用于处理**多环境配置**。

---

## 🎯 核心区别

| 特性 | `spring.profiles.active` | `spring.config.activate.on-profile` |
|------|-------------------------|-------------------------------------|
| **作用** | **激活**哪些 Profile | **标记**配置块属于哪个 Profile |
| **位置** | 在配置文件的**顶部** | 在配置块的**内部** |
| **用途** | 告诉 Spring 使用哪些 Profile | 告诉 Spring 这段配置属于哪个 Profile |
| **语法** | `spring.profiles.active: mysql` | `spring.config.activate.on-profile: mysql` |
| **作用范围** | 整个配置文件 | 当前配置块（`---` 分隔） |

---

## 📝 详细解析

### 1. spring.profiles.active（激活 Profile）

#### 作用

**告诉 Spring Boot 当前激活哪些 Profile**，Spring 会根据这些 Profile 加载对应的配置。

#### 语法

```yaml
spring:
  profiles:
    active: mysql        # 单个 Profile
    # 或
    active: 
      - mysql
      - dev              # 多个 Profile
```

#### 位置

通常在配置文件的**顶部**或**默认配置区域**。

#### 示例

```yaml
# application.yaml
spring:
  profiles:
    active: postgresql   # ← 激活 postgresql Profile

# 默认配置
datasource:
  url: jdbc:postgresql://localhost:5432/db
```

#### 工作原理

```
1. Spring Boot 读取 spring.profiles.active
   ↓
2. 设置激活的 Profile 列表
   ↓
3. 加载匹配的配置块
   ↓
4. 应用配置
```

---

### 2. spring.config.activate.on-profile（标记 Profile）

#### 作用

**标记一段配置属于哪个 Profile**，只有当该 Profile 被激活时，这段配置才会生效。

#### 语法

```yaml
---
spring:
  config:
    activate:
      on-profile: mysql   # ← 标记这段配置属于 mysql Profile

# 这段配置只在 mysql Profile 激活时生效
datasource:
  driver-class-name: com.mysql.cj.jdbc.Driver
  url: jdbc:mysql://localhost:3306/db
```

#### 位置

在配置块的**内部**，通常用 `---` 分隔不同的配置块。

#### 示例

```yaml
# 默认配置（所有 Profile 都生效）
spring:
  profiles:
    active: postgresql

datasource:
  url: jdbc:postgresql://localhost:5432/db

---
# MySQL Profile 配置块
spring:
  config:
    activate:
      on-profile: mysql   # ← 标记：这段配置属于 mysql Profile

datasource:
  driver-class-name: com.mysql.cj.jdbc.Driver
  url: jdbc:mysql://localhost:3306/db

---
# PostgreSQL Profile 配置块
spring:
  config:
    activate:
      on-profile: postgresql   # ← 标记：这段配置属于 postgresql Profile

datasource:
  driver-class-name: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/db
```

#### 工作原理

```
1. Spring Boot 读取配置块
   ↓
2. 检查 spring.config.activate.on-profile
   ↓
3. 如果标记的 Profile 在激活列表中 → 应用这段配置
   ↓
4. 如果标记的 Profile 不在激活列表中 → 忽略这段配置
```

---

## 🔄 完整工作流程

### 示例配置

```yaml
# ===== 第一部分：默认配置 =====
spring:
  profiles:
    active: postgresql   # ← 激活 postgresql Profile

# 默认配置（所有 Profile 都生效）
server:
  port: 8080

datasource:
  username: root
  password: root

# ===== 第二部分：MySQL Profile 配置块 =====
---
spring:
  config:
    activate:
      on-profile: mysql   # ← 标记：这段配置属于 mysql Profile

# 这段配置只在 mysql Profile 激活时生效
datasource:
  driver-class-name: com.mysql.cj.jdbc.Driver
  url: jdbc:mysql://localhost:3306/dolphinscheduler

# ===== 第三部分：PostgreSQL Profile 配置块 =====
---
spring:
  config:
    activate:
      on-profile: postgresql   # ← 标记：这段配置属于 postgresql Profile

# 这段配置只在 postgresql Profile 激活时生效
datasource:
  driver-class-name: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/dolphinscheduler
```

### 执行流程

```
1. Spring Boot 读取配置文件
   ↓
2. 发现 spring.profiles.active: postgresql
   ↓
3. 设置激活的 Profile = ["postgresql"]
   ↓
4. 加载默认配置（第一部分）
   ↓
5. 检查第二部分（mysql Profile）
   → on-profile: mysql
   → 激活列表中没有 "mysql"
   → ❌ 忽略这部分配置
   ↓
6. 检查第三部分（postgresql Profile）
   → on-profile: postgresql
   → 激活列表中有 "postgresql"
   → ✅ 应用这部分配置
   ↓
7. 最终配置（合并后）
   server:
     port: 8080
   datasource:
     username: root
     password: root
     driver-class-name: org.postgresql.Driver
     url: jdbc:postgresql://localhost:5432/dolphinscheduler
```

---

## 🎨 实际应用示例

### DolphinScheduler 中的使用

#### 示例 1：dolphinscheduler-tools/application.yaml

```yaml
# 默认配置
spring:
  main:
    banner-mode: off
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://127.0.0.1:5432/dolphinscheduler

# MySQL Profile 配置块
---
spring:
  config:
    activate:
      on-profile: mysql   # ← 标记：这段配置属于 mysql Profile
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/dolphinscheduler

# PostgreSQL Profile 配置块
---
spring:
  config:
    activate:
      on-profile: postgresql   # ← 标记：这段配置属于 postgresql Profile
  datasource:
    driver-class-name: org.postgresql.Driver
```

**说明**：
- 默认配置使用 PostgreSQL
- 如果激活 `mysql` Profile，会覆盖为 MySQL 配置
- 如果激活 `postgresql` Profile，会覆盖为 PostgreSQL 配置

#### 示例 2：dolphinscheduler-api/application.yaml

```yaml
# 默认配置
spring:
  profiles:
    active: postgresql   # ← 激活 postgresql Profile
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://127.0.0.1:5432/dolphinscheduler

# MySQL Profile 配置块
---
spring:
  config:
    activate:
      on-profile: mysql   # ← 标记：这段配置属于 mysql Profile
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/dolphinscheduler
  quartz:
    properties:
      org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
```

---

## 🔍 共同点

### 1. 都用于多环境配置

两者都用于实现**不同环境使用不同配置**的需求。

### 2. 都基于 Spring Profile 机制

两者都依赖 Spring 的 Profile 机制：
- `spring.profiles.active` 设置激活的 Profile
- `spring.config.activate.on-profile` 标记配置属于哪个 Profile

### 3. 都可以指定多个 Profile

```yaml
# spring.profiles.active 支持多个
spring:
  profiles:
    active: 
      - mysql
      - dev

# spring.config.activate.on-profile 也支持多个
spring:
  config:
    activate:
      on-profile: 
        - mysql
        - dev
```

### 4. 都支持 Profile 表达式

```yaml
# 支持逻辑表达式
spring:
  config:
    activate:
      on-profile: "mysql | postgresql"   # mysql 或 postgresql
      # 或
      on-profile: "!prod"                 # 非 prod
```

---

## ⚠️ 区别总结

### 1. 作用方向不同

| 配置项 | 作用方向 | 说明 |
|--------|---------|------|
| `spring.profiles.active` | **主动激活** | "我要使用这些 Profile" |
| `spring.config.activate.on-profile` | **被动标记** | "这段配置属于这些 Profile" |

### 2. 配置位置不同

```yaml
# spring.profiles.active：通常在顶部
spring:
  profiles:
    active: mysql

# spring.config.activate.on-profile：在配置块内部
---
spring:
  config:
    activate:
      on-profile: mysql
```

### 3. 作用范围不同

| 配置项 | 作用范围 | 说明 |
|--------|---------|------|
| `spring.profiles.active` | **全局** | 影响整个应用的 Profile 激活状态 |
| `spring.config.activate.on-profile` | **局部** | 只影响当前配置块（`---` 分隔） |

### 4. 使用场景不同

#### spring.profiles.active 适用于

- ✅ 设置默认激活的 Profile
- ✅ 通过命令行参数覆盖：`--spring.profiles.active=mysql`
- ✅ 通过环境变量设置：`SPRING_PROFILES_ACTIVE=mysql`

#### spring.config.activate.on-profile 适用于

- ✅ 在同一文件中组织多个环境的配置
- ✅ 使用 `---` 分隔不同的配置块
- ✅ 避免创建多个配置文件（`application-mysql.yaml`、`application-postgresql.yaml`）

---

## 💡 最佳实践

### 方式 1：单一文件 + on-profile（推荐）

```yaml
# application.yaml
spring:
  profiles:
    active: postgresql   # 默认激活 postgresql

# 默认配置
server:
  port: 8080

---
spring:
  config:
    activate:
      on-profile: mysql
datasource:
  driver-class-name: com.mysql.cj.jdbc.Driver

---
spring:
  config:
    activate:
      on-profile: postgresql
datasource:
  driver-class-name: org.postgresql.Driver
```

**优点**：
- ✅ 所有配置在一个文件中
- ✅ 易于对比不同环境的配置
- ✅ 减少文件数量

### 方式 2：多文件 + active

```yaml
# application.yaml
spring:
  profiles:
    active: postgresql

# application-mysql.yaml
datasource:
  driver-class-name: com.mysql.cj.jdbc.Driver

# application-postgresql.yaml
datasource:
  driver-class-name: org.postgresql.Driver
```

**优点**：
- ✅ 配置文件分离，更清晰
- ✅ 每个环境独立维护

---

## 🔄 配置优先级

### 配置合并规则

```
1. 默认配置（application.yaml）
   ↓
2. Profile 特定配置（application-{profile}.yaml）
   ↓
3. 配置块中的 on-profile 配置（覆盖前面的配置）
   ↓
4. 命令行参数（最高优先级）
```

### 示例

```yaml
# application.yaml
spring:
  profiles:
    active: mysql

datasource:
  url: jdbc:postgresql://localhost:5432/db   # 默认值

---
spring:
  config:
    activate:
      on-profile: mysql
datasource:
  url: jdbc:mysql://localhost:3306/db         # 覆盖默认值
```

**最终结果**：
```yaml
datasource:
  url: jdbc:mysql://localhost:3306/db   # 使用 mysql Profile 的配置
```

---

## 📊 对比表格

| 维度 | spring.profiles.active | spring.config.activate.on-profile |
|------|----------------------|----------------------------------|
| **作用** | 激活 Profile | 标记配置块 |
| **位置** | 配置文件顶部 | 配置块内部 |
| **语法** | `spring.profiles.active: mysql` | `spring.config.activate.on-profile: mysql` |
| **作用范围** | 全局 | 局部（当前配置块） |
| **使用场景** | 设置默认 Profile | 在同一文件中组织多环境配置 |
| **优先级** | 可被命令行覆盖 | 配置块内的配置会覆盖默认配置 |
| **兼容性** | Spring Boot 1.x+ | Spring Boot 2.4+（新语法） |

---

## 🎓 历史演变

### Spring Boot 2.4 之前

```yaml
# 旧语法（已废弃）
spring:
  profiles: mysql   # ❌ 已废弃
```

### Spring Boot 2.4+

```yaml
# 新语法（推荐）
spring:
  config:
    activate:
      on-profile: mysql   # ✅ 新语法
```

**说明**：
- `spring.profiles` 在 Spring Boot 2.4+ 中已废弃
- 推荐使用 `spring.config.activate.on-profile`
- `spring.profiles.active` 仍然有效

---

## 📚 总结

### 核心要点

1. **`spring.profiles.active`**：
   - 用于**激活**哪些 Profile
   - 告诉 Spring 使用哪些环境配置

2. **`spring.config.activate.on-profile`**：
   - 用于**标记**配置块属于哪个 Profile
   - 告诉 Spring 这段配置在哪个 Profile 下生效

3. **两者配合使用**：
   - `spring.profiles.active` 设置激活的 Profile
   - `spring.config.activate.on-profile` 标记配置块
   - Spring 根据激活的 Profile 加载匹配的配置块

### 实际应用

在 DolphinScheduler 中：
- `spring.profiles.active: postgresql` → 激活 PostgreSQL Profile
- `spring.config.activate.on-profile: mysql` → 标记 MySQL 配置块
- Spring 根据激活的 Profile 自动选择对应的数据库插件

---

## 🔗 相关文件

- `dolphinscheduler-api/src/main/resources/application.yaml`
- `dolphinscheduler-tools/src/main/resources/application.yaml`
- `dolphinscheduler-standalone-server/src/main/resources/application.yaml`

---

*最后更新：2024*

