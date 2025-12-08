# Maven ${basedir} 路径解析详解

## 📋 问题

在 `dolphinscheduler-tools.xml` 中，有这样的路径：

```xml
<directory>${basedir}/../dolphinscheduler-dao/src/main/resources</directory>
```

**问题**：`${basedir}` 是如何找到 `dolphinscheduler-dao` 模块的？

---

## 🎯 核心概念

### 1. `${basedir}` 是什么？

`${basedir}` 是 **Maven 的内置变量**，表示**当前项目的根目录**。

**关键点**：
- 在 `dolphinscheduler-tools` 模块中，`${basedir}` 指向 `dolphinscheduler-tools/` 目录
- 不是指向父项目根目录，而是**当前模块的根目录**

### 2. Maven 多模块项目结构

DolphinScheduler 是一个 **Maven 多模块项目**（Multi-Module Project）：

```
dolphinscheduler/                          ← 父项目根目录
├── pom.xml                                ← 父 POM (packaging=pom)
│
├── dolphinscheduler-tools/                ← 模块 1
│   ├── pom.xml                            ← 子模块 POM
│   └── src/main/assembly/
│       └── dolphinscheduler-tools.xml     ← 当前文件位置
│
├── dolphinscheduler-dao/                  ← 模块 2 (目标模块)
│   ├── pom.xml
│   └── src/main/resources/
│       └── sql/                           ← 要复制的文件
│
└── dolphinscheduler-common/               ← 模块 3
    ├── pom.xml
    └── src/main/resources/
        └── *.properties                    ← 要复制的文件
```

---

## 🔍 路径解析过程

### 步骤 1：确定 `${basedir}` 的值

当 Maven 在 `dolphinscheduler-tools` 模块中执行 Assembly 插件时：

```xml
<!-- 在 dolphinscheduler-tools/src/main/assembly/dolphinscheduler-tools.xml 中 -->
<directory>${basedir}/../dolphinscheduler-dao/src/main/resources</directory>
```

**`${basedir}` 的值**：
```
${basedir} = /path/to/dolphinscheduler/dolphinscheduler-tools/
```

**说明**：
- `${basedir}` 指向**当前模块的根目录**
- 即 `dolphinscheduler-tools/` 目录

### 步骤 2：解析相对路径

```xml
${basedir}/../dolphinscheduler-dao/src/main/resources
```

**解析过程**：

```
1. ${basedir}
   = /path/to/dolphinscheduler/dolphinscheduler-tools/

2. ${basedir}/..
   = /path/to/dolphinscheduler/dolphinscheduler-tools/..
   = /path/to/dolphinscheduler/                    ← 父项目根目录

3. ${basedir}/../dolphinscheduler-dao
   = /path/to/dolphinscheduler/dolphinscheduler-dao/  ← 同级模块

4. ${basedir}/../dolphinscheduler-dao/src/main/resources
   = /path/to/dolphinscheduler/dolphinscheduler-dao/src/main/resources/
```

### 步骤 3：最终路径

```
/path/to/dolphinscheduler/dolphinscheduler-dao/src/main/resources/
```

这就是 `dolphinscheduler-dao` 模块的资源目录！

---

## 📊 完整路径解析示例

### 示例 1：复制 SQL 脚本

```xml
<fileSet>
    <directory>${basedir}/../dolphinscheduler-dao/src/main/resources</directory>
    <includes>
        <include>sql/**/*</include>
    </includes>
    <outputDirectory>sql</outputDirectory>
</fileSet>
```

**路径解析**：

```
当前文件位置：
  dolphinscheduler-tools/src/main/assembly/dolphinscheduler-tools.xml

${basedir} = dolphinscheduler-tools/

目标路径：
  ${basedir}/../dolphinscheduler-dao/src/main/resources
  = dolphinscheduler-tools/../dolphinscheduler-dao/src/main/resources
  = dolphinscheduler/dolphinscheduler-dao/src/main/resources
```

**实际文件位置**：
```
dolphinscheduler/
  └── dolphinscheduler-dao/
      └── src/main/resources/
          └── sql/
              ├── dolphinscheduler_mysql.sql
              ├── dolphinscheduler_postgresql.sql
              └── upgrade/
```

### 示例 2：复制 Properties 文件

```xml
<fileSet>
    <directory>${basedir}/../dolphinscheduler-common/src/main/resources</directory>
    <includes>
        <include>**/*.properties</include>
    </includes>
    <outputDirectory>conf</outputDirectory>
</fileSet>
```

**路径解析**：

```
${basedir} = dolphinscheduler-tools/

目标路径：
  ${basedir}/../dolphinscheduler-common/src/main/resources
  = dolphinscheduler-tools/../dolphinscheduler-common/src/main/resources
  = dolphinscheduler/dolphinscheduler-common/src/main/resources
```

---

## 🎨 为什么这样设计？

### 1. 跨模块文件访问

在 Maven 多模块项目中，有时需要：
- ✅ 访问其他模块的资源文件
- ✅ 在打包时包含其他模块的文件
- ✅ 创建跨模块的分发包

### 2. 相对路径的优势

使用 `${basedir}/../` 的优势：

- ✅ **灵活性**：不依赖绝对路径
- ✅ **可移植性**：可以在不同机器上运行
- ✅ **相对性**：基于项目结构，不依赖外部配置

### 3. 为什么不直接用模块名？

**问题**：为什么不直接写 `dolphinscheduler-dao/src/main/resources`？

**答案**：因为 Assembly 插件不知道 Maven 的模块概念，它只认识文件系统路径。

**解决方案**：使用 `${basedir}/..` 先回到父目录，再进入目标模块。

---

## 🔄 路径解析流程图

```
Assembly 插件执行
    ↓
读取 dolphinscheduler-tools.xml
    ↓
遇到 ${basedir}
    ↓
${basedir} = dolphinscheduler-tools/  (当前模块根目录)
    ↓
解析 ${basedir}/../dolphinscheduler-dao
    ↓
${basedir}/.. = dolphinscheduler/  (父项目根目录)
    ↓
../dolphinscheduler-dao = dolphinscheduler/dolphinscheduler-dao/
    ↓
最终路径 = dolphinscheduler/dolphinscheduler-dao/src/main/resources/
    ↓
复制文件到 target/tools/sql/
```

---

## 📝 实际项目结构验证

### 项目根目录结构

```
dolphinscheduler/
├── pom.xml                          ← 父 POM
├── dolphinscheduler-tools/
│   ├── pom.xml
│   └── src/main/assembly/
│       └── dolphinscheduler-tools.xml
├── dolphinscheduler-dao/
│   ├── pom.xml
│   └── src/main/resources/
│       └── sql/
└── dolphinscheduler-common/
    ├── pom.xml
    └── src/main/resources/
        └── *.properties
```

### 路径关系

```
dolphinscheduler-tools/              ← ${basedir}
  └── ..                              ← 上一级目录
      └── dolphinscheduler/           ← 父项目根目录
          ├── dolphinscheduler-tools/ ← 当前模块
          ├── dolphinscheduler-dao/   ← 目标模块
          └── dolphinscheduler-common/ ← 另一个目标模块
```

---

## 💡 关键要点总结

### 1. `${basedir}` 的含义

- ✅ 指向**当前模块的根目录**
- ✅ 不是父项目根目录
- ✅ 是 Maven 的内置变量

### 2. 相对路径解析

```
${basedir}                    → 当前模块根目录
${basedir}/..                 → 父项目根目录
${basedir}/../模块名          → 同级模块目录
```

### 3. 为什么能找到模块？

因为 Maven 多模块项目的**目录结构约定**：
- 所有模块都在父项目根目录下
- 模块名 = 目录名
- 使用相对路径可以访问同级模块

---

## 🔍 验证方法

### 方法 1：查看实际路径

在 Assembly 执行时，Maven 会输出实际解析的路径：

```bash
mvn clean package
```

输出示例：
```
[INFO] Processing fileSet
[INFO]   Directory: /path/to/dolphinscheduler/dolphinscheduler-dao/src/main/resources
[INFO]   Includes: sql/**/*
```

### 方法 2：检查文件系统

```bash
# 在 dolphinscheduler-tools 目录下
cd dolphinscheduler-tools
ls ../dolphinscheduler-dao/src/main/resources/sql/
```

应该能看到 SQL 文件。

### 方法 3：使用 Maven 变量

也可以使用 Maven 的其他变量：

```xml
<!-- 使用 project.basedir (等价于 ${basedir}) -->
<directory>${project.basedir}/../dolphinscheduler-dao/src/main/resources</directory>

<!-- 使用 maven.multiModuleProjectDirectory (父项目根目录) -->
<directory>${maven.multiModuleProjectDirectory}/dolphinscheduler-dao/src/main/resources</directory>
```

---

## ⚠️ 注意事项

### 1. 路径必须存在

如果 `dolphinscheduler-dao` 模块不存在或路径错误，Assembly 会失败：

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-assembly-plugin
[ERROR] Directory does not exist: .../dolphinscheduler-dao/src/main/resources
```

### 2. 相对路径的局限性

- ⚠️ 只能在多模块项目中使用
- ⚠️ 依赖目录结构约定
- ⚠️ 如果模块被移动，路径会失效

### 3. 更好的替代方案

如果模块结构可能变化，可以考虑：

```xml
<!-- 使用 Maven 属性 -->
<directory>${maven.multiModuleProjectDirectory}/dolphinscheduler-dao/src/main/resources</directory>
```

或者使用 Maven 的 `maven-dependency-plugin` 来复制依赖模块的资源。

---

## 📚 总结

### 核心答案

**`${basedir}/../dolphinscheduler-dao` 如何找到模块？**

1. `${basedir}` = `dolphinscheduler-tools/` (当前模块根目录)
2. `${basedir}/..` = `dolphinscheduler/` (父项目根目录)
3. `${basedir}/../dolphinscheduler-dao` = `dolphinscheduler/dolphinscheduler-dao/` (同级模块)

**关键**：基于 Maven 多模块项目的**目录结构约定**，使用相对路径访问同级模块。

---

## 🔗 相关文件

- `dolphinscheduler-tools.xml` - Assembly 描述文件
- `pom.xml` (父项目) - 定义所有模块
- `dolphinscheduler-dao/src/main/resources/` - 目标资源目录

---

*最后更新：2024*

