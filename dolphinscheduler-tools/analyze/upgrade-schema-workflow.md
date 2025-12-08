# upgrade-schema.sh 与 target/tools 完整工作流程

## 📋 概述

本文档详细解释 `upgrade-schema.sh` 脚本如何与 Maven Assembly 生成的 `target/tools/` 目录结合，完成数据库升级的完整流程。

---

## 🏗️ 第一步：Maven Assembly 打包

### 执行命令

```bash
mvn clean package
```

### Assembly 过程

根据 `dolphinscheduler-tools.xml` 配置，Assembly 插件会：

1. **复制配置文件** → `target/tools/conf/`
2. **复制 Shell 脚本** → `target/tools/bin/` (设置可执行权限)
3. **复制 SQL 脚本** → `target/tools/sql/`
4. **复制所有依赖 jar** → `target/tools/libs/`

### 最终目录结构

```
target/tools/
├── bin/                              ← Shell 脚本目录
│   ├── create-demo-processes.sh
│   ├── migrate-lineage.sh
│   ├── migrate-resource.sh
│   └── upgrade-schema.sh            ← 升级脚本（可执行）
│
├── conf/                             ← 配置文件目录
│   ├── application.yaml              ← 数据库配置
│   └── common.properties
│
├── libs/                             ← 所有依赖 jar
│   ├── dolphinscheduler-tools-*.jar
│   ├── dolphinscheduler-dao-*.jar
│   ├── spring-boot-*.jar
│   ├── mysql-connector-*.jar
│   └── ... (246 个 jar 文件)
│
└── sql/                              ← SQL 脚本目录
    └── sql/
        ├── dolphinscheduler_mysql.sql
        ├── dolphinscheduler_postgresql.sql
        ├── dolphinscheduler_h2.sql
        ├── soft_version               ← 当前软件版本
        └── upgrade/                   ← 升级脚本目录
            ├── 3.0.0_schema/
            │   ├── mysql/
            │   │   ├── dolphinscheduler_ddl.sql
            │   │   └── dolphinscheduler_dml.sql
            │   └── postgresql/
            │       ├── dolphinscheduler_ddl.sql
            │       └── dolphinscheduler_dml.sql
            ├── 3.0.2_schema/
            ├── 3.1.0_schema/
            ├── 3.1.1_schema/
            ├── 3.2.0_schema/
            ├── 3.2.1_schema/
            ├── 3.2.2_schema/
            └── 3.3.0_schema/
```

---

## 🚀 第二步：执行 upgrade-schema.sh

### 脚本位置

```
target/tools/bin/upgrade-schema.sh
```

### 脚本内容解析

```bash
#!/bin/bash

# 1. 获取脚本所在目录
BIN_DIR=$(dirname $0)
# BIN_DIR = target/tools/bin/

# 2. 获取 DolphinScheduler 主目录（向上两级）
DOLPHINSCHEDULER_HOME=${DOLPHINSCHEDULER_HOME:-$(cd ${BIN_DIR}/../..;pwd)}
# DOLPHINSCHEDULER_HOME = target/ 或 用户指定的目录

# 3. 获取 Tools 目录（向上一级）
TOOLS_HOME=$(cd ${BIN_DIR}/..;pwd)
# TOOLS_HOME = target/tools/

# 4. 加载环境变量（如果不是 Docker 环境）
if [ "$DOCKER" != "true" ]; then
  source "$DOLPHINSCHEDULER_HOME/bin/env/dolphinscheduler_env.sh"
fi

# 5. 设置 JVM 参数
JAVA_OPTS=${JAVA_OPTS:-"-server -Duser.timezone=${SPRING_JACKSON_TIME_ZONE} -Xms1g -Xmx1g -Xmn512m -XX:+PrintGCDetails -Xloggc:gc.log -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dump.hprof"}

# 6. 执行 Java 程序
$JAVA_HOME/bin/java $JAVA_OPTS \
  -cp "$TOOLS_HOME/conf":"$TOOLS_HOME/sql":"$TOOLS_HOME/libs/*" \
  -Dspring.profiles.active=upgrade,${DATABASE} \
  org.apache.dolphinscheduler.tools.datasource.UpgradeDolphinScheduler
```

### 关键路径解析

#### 1. `BIN_DIR` - 脚本目录

```bash
BIN_DIR=$(dirname $0)
```

**执行结果**：
```
BIN_DIR = target/tools/bin/
```

**说明**：
- `$0` = 当前脚本路径（`target/tools/bin/upgrade-schema.sh`）
- `dirname $0` = 脚本所在目录（`target/tools/bin/`）

#### 2. `TOOLS_HOME` - Tools 根目录

```bash
TOOLS_HOME=$(cd ${BIN_DIR}/..;pwd)
```

**执行结果**：
```
TOOLS_HOME = target/tools/
```

**说明**：
- `cd ${BIN_DIR}/..` = 进入 `target/tools/` 目录
- `pwd` = 获取当前绝对路径

#### 3. `DOLPHINSCHEDULER_HOME` - 主目录

```bash
DOLPHINSCHEDULER_HOME=${DOLPHINSCHEDULER_HOME:-$(cd ${BIN_DIR}/../..;pwd)}
```

**执行结果**：
```
DOLPHINSCHEDULER_HOME = target/ 或 用户设置的环境变量值
```

**说明**：
- `${VAR:-default}` = 如果 VAR 未设置，使用 default
- `cd ${BIN_DIR}/../..` = 进入 `target/` 目录（向上两级）

---

## 🔧 第三步：Java 程序启动

### Classpath 配置

```bash
-cp "$TOOLS_HOME/conf":"$TOOLS_HOME/sql":"$TOOLS_HOME/libs/*"
```

**解析结果**：
```
-cp "target/tools/conf":"target/tools/sql":"target/tools/libs/*"
```

**说明**：
- `conf/` = 配置文件目录（包含 `application.yaml`）
- `sql/` = SQL 脚本目录（包含所有升级脚本）
- `libs/*` = 所有依赖 jar 文件

### Spring Profile 配置

```bash
-Dspring.profiles.active=upgrade,${DATABASE}
```

**说明**：
- `upgrade` = 激活升级模式（触发 `UpgradeRunner`）
- `${DATABASE}` = 数据库类型（`mysql`、`postgresql`、`h2`）

**示例**：
```bash
# MySQL
-Dspring.profiles.active=upgrade,mysql

# PostgreSQL
-Dspring.profiles.active=upgrade,postgresql
```

### 主类

```bash
org.apache.dolphinscheduler.tools.datasource.UpgradeDolphinScheduler
```

---

## 🔄 第四步：Java 程序执行流程

### 1. Spring Boot 启动

```java
@SpringBootApplication
public class UpgradeDolphinScheduler {
    public static void main(String[] args) {
        SpringApplication.run(UpgradeDolphinScheduler.class, args);
    }
}
```

**执行过程**：
1. 加载 `application.yaml`（从 `conf/` 目录）
2. 根据 Profile 激活数据库插件（`mysql` 或 `postgresql`）
3. 创建 DataSource 连接数据库
4. 初始化 Spring 容器

### 2. CommandLineRunner 执行

```java
@Component
@Profile("upgrade")  // 只有 upgrade profile 激活时才执行
static class UpgradeRunner implements CommandLineRunner {
    @Override
    public void run(String... args) throws Exception {
        if (dolphinSchedulerManager.schemaIsInitialized()) {
            // 数据库已初始化，执行升级
            dolphinSchedulerManager.upgradeDolphinScheduler();
        } else {
            // 数据库未初始化，执行初始化
            dolphinSchedulerManager.initDolphinScheduler();
        }
    }
}
```

### 3. 检查数据库状态

```java
public boolean schemaIsInitialized() {
    // 检查版本表是否存在
    if (databaseDialect.tableExists("t_escheduler_version")
            || databaseDialect.tableExists("t_ds_version")
            || databaseDialect.tableExists("t_escheduler_queue")) {
        return true;  // 已初始化
    }
    return false;  // 未初始化
}
```

---

## 📊 第五步：数据库初始化或升级

### 场景 1：数据库未初始化（首次安装）

#### 执行流程

```java
dolphinSchedulerManager.initDolphinScheduler();
    ↓
upgradeDao.initSchema();
    ↓
执行完整 SQL 脚本
```

#### SQL 脚本路径

```java
String sqlFilePath = String.format("sql/dolphinscheduler_%s.sql", dbType.getDb());
// 例如：sql/dolphinscheduler_mysql.sql
```

#### 实际文件位置

```
target/tools/sql/sql/dolphinscheduler_mysql.sql
```

**说明**：
- `sql/` 在 classpath 中（通过 `-cp` 添加）
- Spring 的 `ClassPathResource` 可以从 classpath 读取文件
- 路径：`sql/dolphinscheduler_mysql.sql`（相对于 classpath）

---

### 场景 2：数据库已初始化（升级）

#### 执行流程

```java
dolphinSchedulerManager.upgradeDolphinScheduler();
    ↓
1. 获取当前数据库版本
2. 获取所有升级脚本列表
3. 按版本顺序执行升级脚本
```

#### 步骤 1：获取当前版本

```java
String version = upgradeDao.getCurrentVersion("t_ds_version");
// 例如：返回 "3.2.0"
```

#### 步骤 2：获取所有升级脚本

```java
List<String> schemaList = SchemaUtils.getAllSchemaList();
```

**实现逻辑**：

```java
public static List<String> getAllSchemaList() throws IOException {
    // 从 classpath 读取 sql/upgrade 目录
    final File[] schemaDirArr = new ClassPathResource("sql/upgrade").getFile().listFiles();
    
    // 返回所有版本目录，按版本号排序
    return Arrays.stream(schemaDirArr)
        .map(File::getName)
        .sorted((o1, o2) -> {
            // 按版本号排序：3.0.0 < 3.0.2 < 3.1.0 < ...
        })
        .collect(Collectors.toList());
}
```

**实际目录结构**：

```
target/tools/sql/sql/upgrade/
├── 3.0.0_schema/
├── 3.0.2_schema/
├── 3.1.0_schema/
├── 3.1.1_schema/
├── 3.2.0_schema/
├── 3.2.1_schema/
├── 3.2.2_schema/
└── 3.3.0_schema/
```

**返回结果**：
```java
["3.0.0_schema", "3.0.2_schema", "3.1.0_schema", "3.1.1_schema", 
 "3.2.0_schema", "3.2.1_schema", "3.2.2_schema", "3.3.0_schema"]
```

#### 步骤 3：执行升级脚本

```java
for (String schemaDir : schemaList) {
    schemaVersion = schemaDir.split("_")[0];  // "3.2.0"
    
    // 只升级比当前版本高的版本
    if (SchemaUtils.isAGreatVersion(schemaVersion, version)) {
        log.info("upgrade from {} to {}", version, schemaVersion);
        
        // 执行 DDL 和 DML
        upgradeDao.upgradeDolphinScheduler(schemaDir);
        
        // 执行版本特定的升级逻辑
        DolphinSchedulerVersion.getVersion(schemaVersion)
            .ifPresent(v -> upgraderMap.get(v).doUpgrade());
        
        version = schemaVersion;  // 更新当前版本
    }
}
```

#### 升级脚本执行

```java
public void upgradeDolphinScheduler(String schemaDir) {
    // 1. 执行 DDL（表结构变更）
    upgradeDolphinSchedulerDDL(schemaDir, "dolphinscheduler_ddl.sql");
    
    // 2. 执行 DML（数据迁移）
    upgradeDolphinSchedulerDML(schemaDir, "dolphinscheduler_dml.sql");
}
```

**SQL 文件路径**：

```java
// DDL 路径
String sqlFilePath = String.format("sql/upgrade/%s/%s/%s", 
    schemaDir,           // "3.2.0_schema"
    dbType.getDb(),     // "mysql" 或 "postgresql"
    scriptFile);        // "dolphinscheduler_ddl.sql"

// 实际路径示例
// sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_ddl.sql
```

**实际文件位置**：

```
target/tools/sql/sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_ddl.sql
```

---

## 🔍 完整路径映射

### Classpath 与文件系统路径

| Classpath 路径 | 实际文件系统路径 |
|---------------|-----------------|
| `sql/dolphinscheduler_mysql.sql` | `target/tools/sql/sql/dolphinscheduler_mysql.sql` |
| `sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_ddl.sql` | `target/tools/sql/sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_ddl.sql` |
| `application.yaml` | `target/tools/conf/application.yaml` |

**说明**：
- Classpath 路径是**相对于 classpath 根目录**的
- `-cp "target/tools/conf":"target/tools/sql":"target/tools/libs/*"` 将这三个目录添加到 classpath
- Spring 的 `ClassPathResource` 可以从这些目录读取文件

---

## 📋 完整执行流程图

```
┌─────────────────────────────────────────────────────────────┐
│  步骤 1: Maven Assembly 打包                                 │
│  mvn clean package                                           │
│    ↓                                                          │
│  生成 target/tools/ 目录结构                                  │
│    ├── bin/upgrade-schema.sh                                 │
│    ├── conf/application.yaml                                 │
│    ├── libs/*.jar                                            │
│    └── sql/sql/...                                           │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 2: 执行 upgrade-schema.sh                             │
│  cd target/tools/                                            │
│  ./bin/upgrade-schema.sh                                     │
│    ↓                                                          │
│  1. 解析路径变量                                             │
│     BIN_DIR = target/tools/bin/                             │
│     TOOLS_HOME = target/tools/                              │
│    ↓                                                          │
│  2. 设置 Classpath                                           │
│     -cp "conf":"sql":"libs/*"                                │
│    ↓                                                          │
│  3. 启动 Java 程序                                           │
│     java -cp ... UpgradeDolphinScheduler                    │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 3: Spring Boot 启动                                    │
│  1. 加载 application.yaml (从 conf/)                        │
│  2. 根据 Profile 激活数据库插件                             │
│  3. 创建 DataSource 连接数据库                              │
│  4. 初始化 Spring 容器                                       │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 4: UpgradeRunner 执行                                  │
│  @Profile("upgrade") UpgradeRunner.run()                    │
│    ↓                                                          │
│  检查数据库是否已初始化                                      │
│    ├── 是 → 执行升级流程                                     │
│    └── 否 → 执行初始化流程                                   │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 5A: 初始化流程（首次安装）                              │
│  initDolphinScheduler()                                      │
│    ↓                                                          │
│  执行 sql/dolphinscheduler_mysql.sql                         │
│  (从 classpath 读取，实际路径: sql/sql/dolphinscheduler_mysql.sql) │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 5B: 升级流程（已有数据库）                              │
│  upgradeDolphinScheduler()                                  │
│    ↓                                                          │
│  1. 获取当前版本 (从数据库 t_ds_version 表)                  │
│  2. 获取所有升级脚本 (从 sql/upgrade/ 目录)                  │
│  3. 按版本顺序执行升级脚本                                    │
│     - sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_ddl.sql │
│     - sql/upgrade/3.2.0_schema/mysql/dolphinscheduler_dml.sql │
│     - sql/upgrade/3.2.1_schema/mysql/dolphinscheduler_ddl.sql │
│     - ...                                                     │
└─────────────────────────────────────────────────────────────┘
```

---

## 💡 关键要点总结

### 1. 路径解析

- **`${basedir}`** = 当前模块根目录（`dolphinscheduler-tools/`）
- **`${basedir}/..`** = 父项目根目录（`dolphinscheduler/`）
- **`${basedir}/../dolphinscheduler-dao`** = 同级模块目录

### 2. Assembly 打包

- 将所有需要的文件复制到 `target/tools/`
- 保持目录结构
- 设置脚本可执行权限

### 3. Classpath 配置

```bash
-cp "$TOOLS_HOME/conf":"$TOOLS_HOME/sql":"$TOOLS_HOME/libs/*"
```

- `conf/` = 配置文件目录
- `sql/` = SQL 脚本目录
- `libs/*` = 所有依赖 jar

### 4. SQL 脚本路径

- **Classpath 路径**：`sql/dolphinscheduler_mysql.sql`
- **实际路径**：`target/tools/sql/sql/dolphinscheduler_mysql.sql`
- **为什么有双 `sql/`**：Assembly 配置中 `outputDirectory=sql`，而源文件也在 `sql/` 目录下

### 5. 升级流程

1. 检查数据库是否已初始化
2. 如果未初始化 → 执行完整 SQL 脚本
3. 如果已初始化 → 按版本顺序执行升级脚本
4. 更新版本号到最新版本

---

## 🔗 相关文件

- `dolphinscheduler-tools.xml` - Assembly 描述文件
- `upgrade-schema.sh` - 升级脚本
- `UpgradeDolphinScheduler.java` - 主类
- `DolphinSchedulerManager.java` - 升级管理器
- `UpgradeDao.java` - 数据库操作
- `SchemaUtils.java` - 版本工具类

---

*最后更新：2024*

