# Spring 依赖注入机制 - MasterRpcServer 初始化详解

## 问题

`MasterRpcServer` 的构造函数：
```java
@Component
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery {
    public MasterRpcServer(MasterConfig masterConfig) {
        super(NettyServerConfig.builder()...);
    }
}
```

**没有看到显式调用，它是如何被初始化的？**

---

## 答案：Spring 自动依赖注入

这是 **Spring 的构造函数注入（Constructor Injection）**机制。Spring 会自动：
1. 扫描 `@Component` 注解的类
2. 分析构造函数的参数
3. 查找对应的 Bean
4. 自动调用构造函数创建实例

---

## 详细流程

### 1. Spring 组件扫描

#### 启动类：MasterServer

```java
@SpringBootApplication  // 这个注解包含了 @ComponentScan
public class MasterServer {
    public static void main(String[] args) {
        SpringApplication.run(MasterServer.class);  // 启动 Spring 容器
    }
}
```

**`@SpringBootApplication` 包含**:
- `@ComponentScan`: 自动扫描当前包及子包下的所有 `@Component`、`@Service`、`@Repository`、`@Controller` 等注解的类
- 默认扫描路径：`MasterServer` 所在包 `org.apache.dolphinscheduler.server.master` 及其所有子包

#### 扫描到的类

Spring 在扫描过程中会发现：

```java
// 1. MasterConfig - 配置类
@Configuration
@ConfigurationProperties(prefix = "master")
public class MasterConfig {
    private int listenPort = 5678;
    // ...
}

// 2. MasterRpcServer - 组件类
@Component
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery {
    public MasterRpcServer(MasterConfig masterConfig) {  // 需要 MasterConfig
        // ...
    }
}
```

### 2. Bean 创建顺序

Spring 按照依赖关系创建 Bean：

```
步骤 1: 创建 MasterConfig Bean
    │
    ├─> 发现 @Configuration 注解
    ├─> 创建 MasterConfig 实例
    ├─> 从配置文件加载属性（@ConfigurationProperties）
    └─> 注册到 Spring 容器

步骤 2: 创建 MasterRpcServer Bean
    │
    ├─> 发现 @Component 注解
    ├─> 分析构造函数参数：需要 MasterConfig
    ├─> 从容器中查找 MasterConfig Bean（步骤 1 已创建）
    ├─> 调用构造函数：new MasterRpcServer(masterConfig)
    └─> 注册到 Spring 容器
```

### 3. 构造函数注入过程

#### Spring 内部实现（简化版）

```java
// Spring 容器内部逻辑（伪代码）
public class SpringContainer {
    
    public void initializeBeans() {
        // 1. 创建 MasterConfig
        MasterConfig masterConfig = new MasterConfig();
        // 从配置文件加载属性
        masterConfig.setListenPort(5678);
        // 注册到容器
        beanMap.put("masterConfig", masterConfig);
        
        // 2. 创建 MasterRpcServer
        // 发现需要 MasterConfig 参数
        MasterConfig config = beanMap.get("masterConfig");  // 从容器获取
        MasterRpcServer rpcServer = new MasterRpcServer(config);  // 调用构造函数
        // 注册到容器
        beanMap.put("masterRpcServer", rpcServer);
    }
}
```

#### 实际调用链

```
SpringApplication.run(MasterServer.class)
    │
    ├─> 创建 ApplicationContext
    │
    ├─> 扫描组件（Component Scan）
    │   │
    │   ├─> 发现 @Configuration: MasterConfig
    │   │   └─> 创建 MasterConfig Bean
    │   │
    │   └─> 发现 @Component: MasterRpcServer
    │       └─> 分析构造函数依赖
    │
    ├─> 创建 Bean（按依赖顺序）
    │   │
    │   ├─> 1. MasterConfig
    │   │   └─> new MasterConfig()
    │   │
    │   └─> 2. MasterRpcServer
    │       ├─> 查找 MasterConfig Bean
    │       ├─> new MasterRpcServer(masterConfig)  ← 这里调用构造函数
    │       └─> 注册到容器
    │
    └─> Bean 创建完成
```

---

## 关键注解说明

### @Component

```java
@Component
public class MasterRpcServer {
    // Spring 会自动扫描并创建这个类的 Bean
}
```

**作用**:
- 标记类为 Spring 组件
- Spring 会自动创建实例并管理生命周期
- 可以通过 `@Autowired` 注入到其他类

### @Configuration

```java
@Configuration
@ConfigurationProperties(prefix = "master")
public class MasterConfig {
    // Spring 会创建这个配置类的 Bean
    // 并从配置文件加载属性
}
```

**作用**:
- 标记类为配置类
- Spring 会创建实例
- `@ConfigurationProperties` 会从配置文件（如 `application.yaml`）加载属性

### @SpringBootApplication

```java
@SpringBootApplication
public class MasterServer {
    // 这个注解包含：
    // - @ComponentScan: 自动扫描组件
    // - @EnableAutoConfiguration: 自动配置
    // - @SpringBootConfiguration: Spring Boot 配置
}
```

**作用**:
- 启用组件扫描
- 默认扫描当前类所在包及其子包
- 自动发现和注册所有 `@Component` 类

---

## 依赖注入的三种方式

### 1. 构造函数注入（MasterRpcServer 使用的方式）

```java
@Component
public class MasterRpcServer {
    private final MasterConfig masterConfig;
    
    // 构造函数注入
    public MasterRpcServer(MasterConfig masterConfig) {
        this.masterConfig = masterConfig;
    }
}
```

**优点**:
- ✅ 强制依赖，确保对象创建时就有依赖
- ✅ 不可变（final），线程安全
- ✅ 便于测试（可以传入 mock 对象）

### 2. 字段注入

```java
@Component
public class MasterRpcServer {
    @Autowired
    private MasterConfig masterConfig;  // 字段注入
}
```

### 3. Setter 注入

```java
@Component
public class MasterRpcServer {
    private MasterConfig masterConfig;
    
    @Autowired
    public void setMasterConfig(MasterConfig masterConfig) {
        this.masterConfig = masterConfig;
    }
}
```

---

## 完整示例：MasterRpcServer 的创建过程

### 步骤 1: Spring 容器启动

```java
SpringApplication.run(MasterServer.class);
```

### 步骤 2: 组件扫描

Spring 扫描 `org.apache.dolphinscheduler.server.master` 包，发现：

```java
// 发现配置类
@Configuration
@ConfigurationProperties(prefix = "master")
public class MasterConfig { ... }

// 发现组件类
@Component
public class MasterRpcServer { ... }
```

### 步骤 3: 创建 MasterConfig Bean

```java
// Spring 内部执行（伪代码）
MasterConfig masterConfig = new MasterConfig();
// 从 application.yaml 加载配置
// master.listen-port=5678
masterConfig.setListenPort(5678);
// 注册到容器
applicationContext.registerBean("masterConfig", masterConfig);
```

### 步骤 4: 创建 MasterRpcServer Bean

```java
// Spring 内部执行（伪代码）
// 1. 发现需要 MasterConfig 参数
Class<?>[] paramTypes = {MasterConfig.class};

// 2. 从容器获取依赖
MasterConfig masterConfig = applicationContext.getBean(MasterConfig.class);

// 3. 调用构造函数
MasterRpcServer rpcServer = new MasterRpcServer(masterConfig);
// 内部会调用：
//   - super(NettyServerConfig.builder()...)
//   - 创建 NettyRemotingServer
//   - 初始化所有组件

// 4. 注册到容器
applicationContext.registerBean("masterRpcServer", rpcServer);
```

### 步骤 5: 注入到 MasterServer

```java
@SpringBootApplication
public class MasterServer {
    @Autowired
    private MasterRpcServer masterRPCServer;  // Spring 自动注入
    
    @PostConstruct
    public void initialized() {
        this.masterRPCServer.start();  // 使用注入的 Bean
    }
}
```

---

## 验证：如何查看 Bean 创建过程

### 方法 1: 添加日志

```java
@Component
@Slf4j
public class MasterRpcServer extends SpringServerMethodInvokerDiscovery {
    public MasterRpcServer(MasterConfig masterConfig) {
        log.info("MasterRpcServer 构造函数被调用，masterConfig: {}", masterConfig);
        super(NettyServerConfig.builder()
                .serverName("MasterRpcServer")
                .listenPort(masterConfig.getListenPort())
                .build());
    }
}
```

### 方法 2: 使用 Spring Boot Actuator

```yaml
# application.yaml
management:
  endpoints:
    web:
      exposure:
        include: beans
```

访问：`http://localhost:8080/actuator/beans` 查看所有 Bean

### 方法 3: 断点调试

在 `MasterRpcServer` 构造函数中打断点，启动应用时会自动停在这里。

---

## 常见问题

### Q1: 如果 MasterConfig 不存在会怎样？

**A**: Spring 会抛出异常：
```
No qualifying bean of type 'MasterConfig' available
```

### Q2: 如果有多个 MasterConfig Bean 会怎样？

**A**: Spring 会抛出异常：
```
No qualifying bean of type 'MasterConfig' available: 
expected single matching bean but found 2: masterConfig1, masterConfig2
```

**解决**: 使用 `@Primary` 或 `@Qualifier` 指定：

```java
@Component
public class MasterRpcServer {
    public MasterRpcServer(@Qualifier("masterConfig") MasterConfig masterConfig) {
        // ...
    }
}
```

### Q3: 构造函数可以没有参数吗？

**A**: 可以，但需要无参构造函数：

```java
@Component
public class MasterRpcServer {
    @Autowired
    private MasterConfig masterConfig;  // 使用字段注入
    
    public MasterRpcServer() {
        // 无参构造函数
    }
}
```

### Q4: 循环依赖会怎样？

**A**: Spring 会检测并抛出异常：
```
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

---

## 总结

1. **Spring 自动扫描**: `@SpringBootApplication` 启用组件扫描
2. **自动发现**: Spring 发现 `@Component` 和 `@Configuration` 注解的类
3. **依赖分析**: Spring 分析构造函数的参数类型
4. **自动注入**: Spring 从容器中查找对应的 Bean 并注入
5. **调用构造函数**: Spring 自动调用 `new MasterRpcServer(masterConfig)`

**关键点**:
- ✅ 不需要手动调用构造函数
- ✅ Spring 自动管理 Bean 的生命周期
- ✅ 依赖关系自动解析
- ✅ 单例模式（默认每个 Bean 只有一个实例）

这就是 **IoC（控制反转）** 和 **DI（依赖注入）** 的核心思想：**将对象的创建和依赖管理交给框架，而不是在代码中手动创建**。

