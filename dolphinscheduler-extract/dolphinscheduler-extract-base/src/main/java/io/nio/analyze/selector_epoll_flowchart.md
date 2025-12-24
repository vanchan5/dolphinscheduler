# Java NIO Selector epoll 流程图

```mermaid
flowchart TD
    %% 左侧: Selector.open() 分支
    subgraph Branch1["分支1: Selector.open() - Selector初始化"]
        direction TB
        Start1["Selector.open()<br/>🔵 入口"]
        Start1 --> SP1["SelectorProvider.provider()"]
        SP1 --> DSP["DefaultSelectorProvider.create()<br/>💡 Linux系统下是epoll实现"]
        DSP --> ESP["EPollSelectorProvider.openSelector()<br/>💡 返回的selector对象"]
        ESP --> ESI["new EPollSelectorImpl(this)<br/>🔴 创建epoll实现"]
        ESI --> EAU["new EPollArrayWrapper()<br/>💡 创建了Epoll实例,用文件描述符epfd代表"]
        EAU --> EC1["epfd = epollCreate()<br/>💡 调用native方法epollCreate()"]
        EC1 --> JNI1["Java_sun_nio_ch_EPollArrayWrapper_epollCreate<br/>💡 JNI桥接"]
        JNI1 --> EC2["epfd = epoll_create(256)<br/>🔴 Linux内核函数调用"]
        
        Note1["📝 文件描述符(file descriptor)<br/>Linux内核为高效管理已被打开的文件<br/>所创建的索引,用该索引可以找到文件"]
        Note1 -.->|说明| EAU
    end
    
    %% 中间: socketChannel.register() 分支
    subgraph Branch2["分支2: socketChannel.register() - Channel注册"]
        direction TB
        Start2["socketChannel.register(<br/>selector, SelectionKey.OP_READ)<br/>🔵 入口"]
        Start2 --> ASR["((AbstractSelector)sel).register(<br/>this, ops, att)"]
        ASR --> SIR["SelectorImpl.register"]
        SIR --> ESIR["EPollSelectorImpl.implRegister<br/>💡 将fd添加到内部的集合里"]
        ESIR --> PWA["pollWrapper.add(fd)<br/>💡 添加到内部集合"]
        PWA -.->|待select时使用| PP["pollWrapper.poll(timeout)"]
    end
    
    %% 右侧: selector.select() 分支
    subgraph Branch3["分支3: selector.select() - 事件选择"]
        direction TB
        Start3["selector.select()<br/>🔵 入口"]
        Start3 --> SIS["SelectorImpl.select"]
        SIS --> ESDS["EPollSelectorImpl.doSelect"]
        ESDS --> PP["pollWrapper.poll(timeout)<br/>💡 这一步才是真正的注册绑定事件"]
        PP --> UR["updateRegistrations()"]
        UR --> EC3["epollCtl(epfd, opcode, fd, events)<br/>💡 调用native方法epollCtl()进行事件绑定"]
        EC3 --> JNI2["Java_sun_nio_ch_EPollArrayWrapper_epollCtl<br/>💡 JNI桥接"]
        JNI2 --> ECTL["epoll_ctl<br/>🔴 Linux内核函数调用"]
        ECTL --> EW1["updated = epollWait(<br/>pollArrayAddress, NUM_EPOLLEVENTS,<br/>timeout, epfd)<br/>💡 调用native方法epollWait()等待事件"]
        EW1 --> JNI3["Java_sun_nio_ch_EPollArrayWrapper_epollWait<br/>💡 JNI桥接"]
        JNI3 --> EWAIT["epoll_wait<br/>🔴 Linux内核函数调用"]
        
        Note2["📝 当socket收到数据后,中断程序调用回调函数<br/>会给epoll实例的事件就绪列表rdlist里<br/>添加该socket引用(这块操作系统实现的)<br/>当程序执行到epoll_wait时,<br/>如果rdlist已经引用了socket,那么epoll_wait直接返回,<br/>如果rdlist为空,阻塞进程"]
        Note2 -.->|说明| EWAIT
        
        Note3["📝 中断是系统用来响应硬件设备请求的一种机制,<br/>操作系统收到硬件的中断请求,<br/>会打断正在执行的进程,<br/>然后调用内核中的中断处理程序来响应请求"]
        Note3 -.->|说明| EWAIT
    end
    
    %% 样式定义
    classDef greenBox fill:#90EE90,stroke:#333,stroke-width:3px
    classDef whiteBox fill:#FFFFFF,stroke:#333,stroke-width:2px
    classDef redBox fill:#FF6B6B,stroke:#333,stroke-width:3px
    classDef yellowBox fill:#FFD700,stroke:#333,stroke-width:2px
    
    class Start1,Start2,Start3 greenBox
    class SP1,DSP,ESP,EAU,EC1,JNI1,ASR,SIR,ESIR,PWA,SIS,ESDS,PP,UR,EC3,JNI2,EW1,JNI3 whiteBox
    class ESI,EC2,ECTL,EWAIT redBox
    class Note1,Note2,Note3 yellowBox
```

## 流程说明

### 1. Selector.open() 分支（Selector 初始化）
- 从 `Selector.open()` 开始，通过 `SelectorProvider` 获取系统默认的 Selector 提供者
- Linux 系统下使用 epoll 实现，创建 `EPollSelectorImpl` 和 `EPollArrayWrapper`
- 最终调用 Linux 内核函数 `epoll_create` 创建 epoll 实例，返回文件描述符 `epfd`

### 2. socketChannel.register() 分支（Channel 注册）
- 注册 SocketChannel 到 Selector，指定关注的操作类型（如 OP_READ）
- 通过 `EPollSelectorImpl.implRegister` 将文件描述符 `fd` 添加到内部集合
- 这一步只是添加到内部集合，真正的 epoll 注册在 select() 时进行

### 3. selector.select() 分支（事件选择）
- 调用 `select()` 方法等待 I/O 事件
- `updateRegistrations()` 调用 `epoll_ctl` 进行真正的注册和事件绑定
- `epoll_wait` 等待事件就绪，如果就绪列表为空则阻塞进程
- 当 socket 收到数据时，操作系统通过中断机制将 socket 添加到 epoll 的就绪列表

### 关键概念
- **文件描述符 (fd/epfd)**: Linux 内核为管理已打开文件创建的索引
- **中断机制**: 系统响应硬件设备请求的机制
- **epoll_wait**: 如果就绪列表不为空立即返回，为空则阻塞等待

