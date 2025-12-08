# Maven Assembly 插件工作原理详解

## 📋 概述

Maven Assembly 插件是一个**打包工具**，用于创建**自定义的分发包**。它可以将项目代码、依赖、配置文件、脚本等按照指定的目录结构打包成一个可分发的目录或压缩包。

---

## 🎯 核心概念

### 1. 什么是 Assembly？

**Assembly** = **装配、组装**

- 将项目的各个部分（代码、依赖、配置、脚本）**组装**在一起
- 创建一个**可分发的、可执行的**目录结构
- 类似于制作一个"安装包"或"发布包"

### 2. 与普通 Build 的区别

| 特性 | 普通 Build (`mvn package`) | Assembly Build |
|------|-------------------------|----------------|
| **输出** | 单个 jar 文件 | 完整的目录结构 |
| **内容** | 只有编译后的类文件 | 代码 + 依赖 + 配置 + 脚本 |
| **用途** | 作为依赖被其他项目使用 | 作为独立应用分发 |
| **结构** | `project-version.jar` | `bin/`, `conf/`, `libs/`, `sql/` 等 |

---

## 🔍 dolphinscheduler-tools.xml 详细解析

### 文件结构

```xml
<assembly>
    <id>dolphinscheduler-tools</id>           <!-- 装配 ID -->
    <formats>                                 <!-- 输出格式 -->
        <format>dir</format>                  <!-- 目录格式 -->
    </formats>
    <baseDirectory>tools</baseDirectory>      <!-- 基础目录 -->
    <fileSets>                                <!-- 文件集合 -->
        <!-- 配置文件 -->
        <!-- 脚本文件 -->
        <!-- SQL 文件 -->
    </fileSets>
    <dependencySets>                          <!-- 依赖集合 -->
        <!-- 所有依赖 jar -->
    </dependencySets>
</assembly>
```

### 逐行解析

#### 1. 基础配置

```xml
<id>dolphinscheduler-tools</id>
```
- **作用**：标识这个 assembly 的唯一 ID
- **用途**：在 Maven 输出中区分不同的 assembly

```xml
<formats>
    <format>dir</format>
</formats>
```
- **作用**：指定输出格式
- **可选值**：
  - `dir` - 目录格式（解压后的目录）
  - `zip` - ZIP 压缩包
  - `tar.gz` - tar.gz 压缩包
  - `tar.bz2` - tar.bz2 压缩包

```xml
<includeBaseDirectory>false</includeBaseDirectory>
<baseDirectory>tools</baseDirectory>
```
- **作用**：设置输出目录结构
- **说明**：
  - `includeBaseDirectory=false`：不包含项目名称和版本号作为根目录
  - `baseDirectory=tools`：输出目录名为 `tools`

**输出结果**：
```
target/tools/          ← 最终输出目录
├── bin/               ← 脚本目录
├── conf/              ← 配置目录
├── libs/              ← 依赖 jar 目录
└── sql/               ← SQL 脚本目录
```

#### 2. 文件集合 (fileSets)

##### 配置 1：YAML 配置文件

```xml
<fileSet>
    <directory>${basedir}/src/main/resources</directory>
    <includes>
        <include>*.yaml</include>
    </includes>
    <outputDirectory>conf</outputDirectory>
</fileSet>
```

**作用**：
- 从 `src/main/resources` 目录复制所有 `.yaml` 文件
- 输出到 `tools/conf/` 目录

**实际效果**：
```
src/main/resources/
  └── application.yaml
        ↓ 复制
tools/conf/
  └── application.yaml
```

##### 配置 2：Shell 脚本

```xml
<fileSet>
    <directory>${basedir}/src/main/bin</directory>
    <outputDirectory>bin</outputDirectory>
    <fileMode>0755</fileMode>
    <directoryMode>0755</directoryMode>
</fileSet>
```

**作用**：
- 从 `src/main/bin` 目录复制所有脚本文件
- 输出到 `tools/bin/` 目录
- 设置文件权限为 `0755`（可执行）

**实际效果**：
```
src/main/bin/
  ├── create-demo-processes.sh
  ├── migrate-lineage.sh
  ├── migrate-resource.sh
  └── upgrade-schema.sh
        ↓ 复制（设置可执行权限）
tools/bin/
  ├── create-demo-processes.sh  (0755)
  ├── migrate-lineage.sh         (0755)
  ├── migrate-resource.sh        (0755)
  └── upgrade-schema.sh          (0755)
```

##### 配置 3：SQL 脚本

```xml
<fileSet>
    <directory>${basedir}/../dolphinscheduler-dao/src/main/resources</directory>
    <includes>
        <include>sql/**/*</include>
    </includes>
    <outputDirectory>sql</outputDirectory>
</fileSet>
```

**作用**：
- 从 `dolphinscheduler-dao` 模块复制所有 SQL 脚本
- 输出到 `tools/sql/` 目录
- 保持目录结构（`sql/**/*` 表示递归复制）

**实际效果**：
```
../dolphinscheduler-dao/src/main/resources/sql/
  ├── dolphinscheduler_mysql.sql
  ├── dolphinscheduler_postgresql.sql
  ├── dolphinscheduler_h2.sql
  └── upgrade/
      └── 3.0.0_schema/
          └── mysql/
              └── dolphinscheduler_ddl.sql
        ↓ 复制（保持目录结构）
tools/sql/
  ├── dolphinscheduler_mysql.sql
  ├── dolphinscheduler_postgresql.sql
  ├── dolphinscheduler_h2.sql
  └── upgrade/
      └── 3.0.0_schema/
          └── mysql/
              └── dolphinscheduler_ddl.sql
```

##### 配置 4：Properties 配置文件

```xml
<fileSet>
    <directory>${basedir}/../dolphinscheduler-common/src/main/resources</directory>
    <includes>
        <include>**/*.properties</include>
    </includes>
    <outputDirectory>conf</outputDirectory>
</fileSet>
```

**作用**：
- 从 `dolphinscheduler-common` 模块复制所有 `.properties` 文件
- 输出到 `tools/conf/` 目录（与 YAML 文件合并）

#### 3. 依赖集合 (dependencySets)

```xml
<dependencySets>
    <dependencySet>
        <outputDirectory>libs</outputDirectory>
    </dependencySet>
</dependencySets>
```

**作用**：
- 将所有依赖的 jar 文件复制到 `tools/libs/` 目录
- 包括项目自身的 jar 和所有传递依赖

**实际效果**：
```
tools/libs/
  ├── dolphinscheduler-tools-1.0.0.jar
  ├── dolphinscheduler-dao-1.0.0.jar
  ├── spring-boot-starter-2.7.0.jar
  ├── mysql-connector-java-8.0.30.jar
  └── ... (所有依赖)
```

---

## 🔄 完整工作流程

### 步骤 1：Maven 编译

```
mvn clean package
    ↓
1. 编译 Java 代码
2. 运行测试
3. 打包成 jar 文件
```

### 步骤 2：Assembly 插件执行

```
maven-assembly-plugin 在 package 阶段执行
    ↓
1. 读取 dolphinscheduler-tools.xml
2. 收集所有文件（fileSets）
3. 收集所有依赖（dependencySets）
4. 按照配置的目录结构组装
5. 输出到 target/tools/
```

### 步骤 3：最终输出

```
target/
  └── tools/                    ← Assembly 输出
      ├── bin/                  ← Shell 脚本
      │   ├── create-demo-processes.sh
      │   ├── migrate-lineage.sh
      │   ├── migrate-resource.sh
      │   └── upgrade-schema.sh
      ├── conf/                 ← 配置文件
      │   ├── application.yaml
      │   └── *.properties
      ├── libs/                 ← 所有依赖 jar
      │   ├── dolphinscheduler-tools-1.0.0.jar
      │   ├── dolphinscheduler-dao-1.0.0.jar
      │   └── ... (其他依赖)
      └── sql/                  ← SQL 脚本
          ├── dolphinscheduler_mysql.sql
          ├── dolphinscheduler_postgresql.sql
          └── upgrade/
```

---

## 📊 pom.xml 中的配置

### 完整配置解析

```xml
<build>
    <plugins>
        <!-- 1. JAR 插件：打包主 jar -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>*.yaml</exclude>  <!-- 排除 YAML 文件 -->
                </excludes>
            </configuration>
        </plugin>

        <!-- 2. Assembly 插件：创建分发包 -->
        <plugin>
            <artifactId>maven-assembly-plugin</artifactId>
            <executions>
                <execution>
                    <id>dolphinscheduler-tools</id>      <!-- 执行 ID -->
                    <goals>
                        <goal>single</goal>             <!-- 单次执行 -->
                    </goals>
                    <phase>package</phase>              <!-- 在 package 阶段执行 -->
                    <configuration>
                        <finalName>tools</finalName>   <!-- 输出目录名 -->
                        <descriptors>
                            <descriptor>src/main/assembly/dolphinscheduler-tools.xml</descriptor>
                        </descriptors>
                        <appendAssemblyId>false</appendAssemblyId>  <!-- 不追加 ID -->
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 关键配置说明

#### 1. `<goal>single</goal>`

- **作用**：执行一次 assembly
- **说明**：使用指定的 descriptor 文件创建分发包

#### 2. `<phase>package</phase>`

- **作用**：在 `package` 阶段执行
- **说明**：确保在 jar 文件创建之后执行

**Maven 生命周期**：
```
validate → compile → test → package → install → deploy
                              ↑
                        Assembly 在这里执行
```

#### 3. `<finalName>tools</finalName>`

- **作用**：设置输出目录名
- **说明**：最终输出为 `target/tools/`

#### 4. `<appendAssemblyId>false</appendAssemblyId>`

- **作用**：不追加 assembly ID 到输出目录名
- **说明**：
  - `true`：输出为 `target/tools-dolphinscheduler-tools/`
  - `false`：输出为 `target/tools/`

---

## 🔀 Assembly 与 Build 的区别

### 1. 作用范围

#### pom.xml 中的 `<build>`

```xml
<build>
    <plugins>
        <!-- 所有构建插件 -->
    </plugins>
</build>
```

**作用**：
- 定义**整个项目的构建过程**
- 包括编译、测试、打包等所有步骤
- 控制 Maven 生命周期

#### assembly.xml

```xml
<assembly>
    <!-- 分发包的组装规则 -->
</assembly>
```

**作用**：
- 定义**分发包的组装规则**
- 只关注"如何打包分发"
- 不涉及编译和测试

### 2. 执行时机

#### Build 插件

```
mvn clean compile
    ↓
编译阶段执行
```

```
mvn package
    ↓
打包阶段执行
```

#### Assembly 插件

```
mvn package
    ↓
在 package 阶段执行
    ↓
读取 assembly.xml
    ↓
创建分发包
```

### 3. 输出结果

#### 普通 Build

```
target/
  └── dolphinscheduler-tools-1.0.0.jar  ← 单个 jar 文件
```

#### Assembly Build

```
target/
  ├── dolphinscheduler-tools-1.0.0.jar  ← 普通 jar（仍然生成）
  └── tools/                             ← Assembly 输出（目录结构）
      ├── bin/
      ├── conf/
      ├── libs/
      └── sql/
```

### 4. 使用场景

#### 普通 Build 适用于

- ✅ 作为依赖被其他项目使用
- ✅ 发布到 Maven 仓库
- ✅ 简单的库项目

#### Assembly Build 适用于

- ✅ 需要独立运行的应用
- ✅ 需要包含配置文件和脚本
- ✅ 需要分发给用户部署
- ✅ 需要包含所有依赖

---

## 💡 实际应用场景

### 场景 1：数据库迁移工具

DolphinScheduler Tools 是一个**数据库迁移和工具集**，需要：

1. **可执行的脚本**：`upgrade-schema.sh`、`migrate-lineage.sh`
2. **配置文件**：`application.yaml`
3. **SQL 脚本**：数据库初始化脚本
4. **所有依赖**：Spring Boot、数据库驱动等

**使用 Assembly**：
- 将所有内容打包成一个完整的目录
- 用户可以直接运行脚本，无需单独安装依赖

### 场景 2：部署包

```
tools/
  ├── bin/
  │   └── upgrade-schema.sh      ← 用户执行这个脚本
  ├── conf/
  │   └── application.yaml       ← 配置文件
  ├── libs/                      ← 所有依赖（自动包含）
  │   └── *.jar
  └── sql/                       ← SQL 脚本
      └── *.sql
```

**用户使用**：
```bash
cd tools/
./bin/upgrade-schema.sh          # 直接运行，无需安装依赖
```

---

## 🎯 关键优势

### 1. 自动化打包

- ✅ 无需手动复制文件
- ✅ 自动收集所有依赖
- ✅ 保持目录结构

### 2. 可重复构建

- ✅ 每次构建结果一致
- ✅ 不依赖人工操作
- ✅ 适合 CI/CD

### 3. 灵活配置

- ✅ 可以包含任意文件
- ✅ 可以自定义目录结构
- ✅ 可以设置文件权限

### 4. 多格式支持

- ✅ 目录格式（开发测试）
- ✅ ZIP 格式（Windows）
- ✅ tar.gz 格式（Linux）

---

## 📚 总结

### Assembly 的核心价值

1. **创建可分发的应用包**
   - 包含代码、依赖、配置、脚本
   - 用户可以独立运行

2. **自动化打包过程**
   - 无需手动复制文件
   - 自动收集依赖

3. **灵活的目录结构**
   - 可以自定义输出结构
   - 可以包含跨模块的文件

### 与 Build 的关系

- **Build**：定义**如何构建**（编译、测试、打包）
- **Assembly**：定义**如何分发**（组装、打包、输出）

两者**互补**，共同完成从源码到可分发包的完整流程。

---

## 🔗 相关文件

- `dolphinscheduler-tools.xml` - Assembly 描述文件
- `pom.xml` - Maven 构建配置
- `src/main/bin/` - Shell 脚本目录
- `src/main/resources/` - 配置文件目录

---

*最后更新：2024*

