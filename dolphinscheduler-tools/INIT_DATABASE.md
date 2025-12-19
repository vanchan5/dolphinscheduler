# 使用 tools 目录初始化数据库

## 目录结构

编译后的 `dolphinscheduler-tools/target/tools` 目录结构如下：

```
tools/
├── bin/              # 脚本目录
│   ├── upgrade-schema.sh      # 初始化/升级数据库脚本
│   ├── migrate-lineage.sh     # 迁移血缘数据脚本
│   ├── migrate-resource.sh   # 迁移资源脚本
│   └── create-demo-processes.sh  # 创建示例流程脚本
├── conf/             # 配置文件目录
│   ├── application.yaml       # 应用配置文件
│   └── common.properties     # 通用配置文件
├── sql/              # SQL 脚本目录
│   ├── dolphinscheduler_postgresql.sql
│   └── dolphinscheduler_mysql.sql
└── libs/             # 依赖 jar 包目录
```

## 初始化数据库步骤

### 方法一：使用脚本（推荐）

#### 1. 进入 tools 目录

```bash
cd dolphinscheduler-tools/target/tools
```

#### 2. 配置数据库连接

编辑 `conf/application.yaml` 文件，修改数据库连接信息：

**PostgreSQL 示例：**
```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://117.72.107.77:5432/dolphinscheduler-dev
    username: your_username
    password: your_password
```

**MySQL 示例：**
```yaml
spring:
  config:
    activate:
      on-profile: mysql
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/dolphinscheduler?useUnicode=true&characterEncoding=UTF-8
    username: root
    password: root
```

#### 3. 设置环境变量

```bash
# 设置数据库类型（postgresql 或 mysql）
export DATABASE=postgresql

# 设置 Java 路径（如果未设置 JAVA_HOME）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_121.jdk/Contents/Home

# 可选：设置时区
export SPRING_JACKSON_TIME_ZONE=Asia/Shanghai
```

#### 4. 执行初始化脚本

```bash
# 给脚本添加执行权限
chmod +x bin/upgrade-schema.sh

# 执行初始化
./bin/upgrade-schema.sh
```

脚本会自动检测数据库是否已初始化：
- **如果数据库未初始化**：执行 `sql/dolphinscheduler_postgresql.sql` 或 `sql/dolphinscheduler_mysql.sql` 进行初始化
- **如果数据库已初始化**：执行升级操作

### 方法二：直接使用 Java 命令

如果不想使用脚本，可以直接使用 Java 命令：

```bash
cd dolphinscheduler-tools/target/tools

java -cp "conf:sql:libs/*" \
  -Dspring.profiles.active=upgrade,postgresql \
  -Dspring.datasource.url=jdbc:postgresql://117.72.107.77:5432/dolphinscheduler-dev \
  -Dspring.datasource.username=your_username \
  -Dspring.datasource.password=your_password \
  org.apache.dolphinscheduler.tools.datasource.UpgradeDolphinScheduler
```

### 方法三：通过系统属性覆盖配置

也可以通过系统属性覆盖 `application.yaml` 中的配置：

```bash
./bin/upgrade-schema.sh \
  -Dspring.datasource.url=jdbc:postgresql://117.72.107.77:5432/dolphinscheduler-dev \
  -Dspring.datasource.username=your_username \
  -Dspring.datasource.password=your_password
```

## 完整示例

### PostgreSQL 初始化示例

```bash
cd dolphinscheduler-tools/target/tools

# 设置环境变量
export DATABASE=postgresql
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_121.jdk/Contents/Home

# 编辑配置文件（或使用系统属性）
# 修改 conf/application.yaml 中的数据库连接信息

# 执行初始化
chmod +x bin/upgrade-schema.sh
./bin/upgrade-schema.sh
```

### MySQL 初始化示例

```bash
cd dolphinscheduler-tools/target/tools

# 设置环境变量
export DATABASE=mysql
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_121.jdk/Contents/Home

# 编辑配置文件
# 修改 conf/application.yaml 中的数据库连接信息

# 执行初始化
chmod +x bin/upgrade-schema.sh
./bin/upgrade-schema.sh
```

## 注意事项

1. **数据库必须已创建**：执行脚本前，确保目标数据库已经存在（但表可以不存在）

2. **数据库用户权限**：确保数据库用户有创建表、索引等权限

3. **配置文件优先级**：
   - 系统属性（`-Dspring.datasource.url=...`）优先级最高
   - 环境变量（`SPRING_DATASOURCE_URL`）
   - `application.yaml` 配置文件

4. **自动检测**：脚本会自动检测数据库是否已初始化，如果已初始化会执行升级而不是初始化

5. **日志输出**：执行过程中会输出日志，可以通过日志查看初始化进度

## 验证初始化结果

初始化完成后，可以连接数据库验证：

```sql
-- PostgreSQL
\dt  -- 查看所有表

-- MySQL
SHOW TABLES;

-- 查看版本表
SELECT * FROM t_ds_version;
```

## 常见问题

### 1. 找不到 Java

**错误**：`command not found: java`

**解决**：设置 `JAVA_HOME` 环境变量

### 2. 数据库连接失败

**错误**：`Connection refused` 或 `Authentication failed`

**解决**：
- 检查数据库是否启动
- 检查连接地址、端口是否正确
- 检查用户名、密码是否正确
- 检查防火墙设置

### 3. 权限不足

**错误**：`Permission denied: CREATE TABLE`

**解决**：确保数据库用户有足够的权限

### 4. 表已存在

**错误**：`Table already exists`

**解决**：如果表已存在，脚本会自动执行升级而不是初始化

### 实践：启动
```bash
cd /Users/cvte/develop/github/dolphinscheduler/dolphinscheduler-tools/target/tools && java -cp "conf:sql:libs/*" -Dspring.profiles.active=upgrade,postgresql -Dspring.datasource.url=jdbc:postgresql://117.72.107.77:5432/dolphinscheduler-dev -Dspring.datasource.username=root -Dspring.datasource.password=root org.apache.dolphinscheduler.tools.datasource.UpgradeDolphinScheduler
```