# ReentrantLock 工作原理深度解析

## 1. 概述

`ReentrantLock` 是 Java 并发包 `java.util.concurrent.locks` 中提供的一个可重入的互斥锁实现，它提供了比 `synchronized` 更灵活的锁机制，支持公平锁和非公平锁两种模式。

### 1.1 核心特性

- **可重入性（Reentrant）**：同一个线程可以多次获得同一把锁
- **公平性选择**：支持公平锁（Fair Lock）和非公平锁（Nonfair Lock）
- **可中断性**：支持可中断的锁获取操作
- **超时机制**：支持带超时的锁获取操作
- **条件变量**：支持多个条件变量（Condition）

### 1.2 与 synchronized 的对比

| 特性 | ReentrantLock | synchronized |
|------|---------------|--------------|
| 可重入 | ✅ | ✅ |
| 公平锁 | ✅ | ❌ |
| 可中断 | ✅ | ❌ |
| 超时获取 | ✅ | ❌ |
| 多条件变量 | ✅ | ❌ |
| 自动释放 | ❌ | ✅ |
| 性能（Java 6+） | 相近 | 相近 |

## 2. 核心类结构

### 2.1 类继承关系图

```mermaid
classDiagram
    class Lock {
        <<interface>>
        +lock()
        +unlock()
        +tryLock()
        +tryLock(timeout, unit)
        +lockInterruptibly()
        +newCondition()
    }
    
    class ReentrantLock {
        -Sync sync
        +ReentrantLock()
        +ReentrantLock(fair)
        +lock()
        +unlock()
        +tryLock()
        +newCondition()
        +getHoldCount()
        +isHeldByCurrentThread()
        +isLocked()
        +isFair()
    }
    
    class AbstractQueuedSynchronizer {
        <<abstract>>
        -volatile int state
        -Node head
        -Node tail
        +acquire(arg)
        +release(arg)
        +tryAcquire(arg) *
        +tryRelease(arg) *
    }
    
    class Sync {
        <<abstract>>
        +nonfairTryAcquire(acquires)
        +tryRelease(releases)
        +isHeldExclusively()
    }
    
    class NonfairSync {
        +lock()
        +tryAcquire(acquires)
    }
    
    class FairSync {
        +lock()
        +tryAcquire(acquires)
    }
    
    Lock <|.. ReentrantLock
    ReentrantLock *-- Sync : contains
    AbstractQueuedSynchronizer <|-- Sync
    Sync <|-- NonfairSync
    Sync <|-- FairSync
```

### 2.2 内部组件关系图

```mermaid
graph TB
    ReentrantLock[ReentrantLock<br/>对外接口]
    Sync[Sync<br/>同步器基类]
    NonfairSync[NonfairSync<br/>非公平锁实现]
    FairSync[FairSync<br/>公平锁实现]
    AQS[AbstractQueuedSynchronizer<br/>AQS队列同步器]
    CLH[CLH队列<br/>等待队列]
    
    ReentrantLock --> Sync
    Sync --> NonfairSync
    Sync --> FairSync
    Sync --> AQS
    AQS --> CLH
    
    style ReentrantLock fill:#e1f5ff
    style Sync fill:#fff4e1
    style AQS fill:#f0f0f0
    style CLH fill:#e8f5e9
```

## 3. AbstractQueuedSynchronizer (AQS) 核心机制

`ReentrantLock` 的内部实现依赖于 `AbstractQueuedSynchronizer`（AQS），这是 Java 并发包的基石。

### 3.1 AQS 核心数据结构

```mermaid
graph LR
    AQS["AQS"]
    State["state: volatile int<br/>锁状态计数"]
    Head["head: Node<br/>队列头节点"]
    Tail["tail: Node<br/>队列尾节点"]
    Node1["Node: Thread1<br/>prev | next | waitStatus"]
    Node2["Node: Thread2<br/>prev | next | waitStatus"]
    Node3["Node: Thread3<br/>prev | next | waitStatus"]
    
    AQS --> State
    AQS --> Head
    AQS --> Tail
    Head --> Node1
    Node1 --> Node2
    Node2 --> Node3
    Node3 --> Tail
    
    style AQS fill:#ffebee
    style State fill:#e3f2fd
    style Head fill:#e8f5e9
    style Tail fill:#e8f5e9
```

### 3.2 Node 节点结构

```mermaid
classDiagram
    class Node {
        -volatile int waitStatus
        -Node prev
        -Node next
        -Node nextWaiter
        -Thread thread
        +CANCELLED = 1
        +SIGNAL = -1
        +CONDITION = -2
        +PROPAGATE = -3
    }
    
    note for Node "waitStatus状态：<br/>CANCELLED: 节点已取消<br/>SIGNAL: 后续节点需要唤醒<br/>CONDITION: 在条件队列中<br/>PROPAGATE: 共享模式下传播释放"
```

### 3.3 CLH 队列结构

CLH（Craig, Landin, and Hagersten）队列是一个基于链表的 FIFO 队列，用于管理等待获取锁的线程。

```mermaid
graph LR
    subgraph CLH队列
        Head[head<br/>虚节点]
        T1[Thread1<br/>waitStatus: SIGNAL]
        T2[Thread2<br/>waitStatus: SIGNAL]
        T3[Thread3<br/>waitStatus: CANCELLED]
        Tail[tail<br/>虚节点]
        
        Head -->|prev| T1
        T1 -->|next| T2
        T1 -->|prev| Head
        T2 -->|next| T3
        T2 -->|prev| T1
        T3 -->|next| Tail
        T3 -->|prev| T2
        Tail -->|prev| T3
    end
    
    style Head fill:#ffcdd2
    style Tail fill:#ffcdd2
    style T1 fill:#c8e6c9
    style T2 fill:#c8e6c9
    style T3 fill:#bdbdbd
```

### 3.4 AQS 核心数据结构详解

#### 3.4.1 AQS 类的关键字段

```java
public abstract class AbstractQueuedSynchronizer {
    // 锁状态：0表示未锁定，>0表示锁定次数（可重入）
    private volatile int state;
    
    // CLH队列的虚拟头节点（不存储线程，仅作为哨兵）
    private transient volatile Node head;
    
    // CLH队列的尾节点
    private transient volatile Node tail;
    
    // 静态内部类：队列节点
    static final class Node {
        // 节点状态标志
        volatile int waitStatus;
        
        // 前驱节点
        volatile Node prev;
        
        // 后继节点
        volatile Node next;
        
        // 节点关联的线程
        volatile Thread thread;
        
        // 条件队列中的下一个等待节点（用于Condition）
        Node nextWaiter;
        
        // waitStatus的常量值
        static final int CANCELLED =  1;  // 节点已取消
        static final int SIGNAL    = -1; // 后续节点需要被唤醒
        static final int CONDITION = -2; // 节点在条件队列中
        static final int PROPAGATE = -3; // 共享模式下传播释放
    }
}
```

#### 3.4.2 Node 节点状态详解

```mermaid
stateDiagram-v2
    [*] --> 初始状态: waitStatus = 0
    初始状态 --> SIGNAL: shouldParkAfterFailedAcquire<br/>设置为SIGNAL
    初始状态 --> CANCELLED: 线程被中断或超时
    SIGNAL --> CANCELLED: 线程被中断或超时
    SIGNAL --> [*]: 成功获取锁，节点出队
    CANCELLED --> [*]: 节点被清理
    
    note right of SIGNAL
        SIGNAL状态表示：
        当前节点的后继节点需要被唤醒
        当前节点释放锁时，需要唤醒后继节点
    end note
    
    note right of CANCELLED
        CANCELLED状态表示：
        节点对应的线程已取消等待
        该节点需要从队列中移除
    end note
```

**waitStatus 状态说明：**

| 状态值 | 常量名 | 含义 | 使用场景 |
|--------|--------|------|----------|
| 0 | 初始状态 | 新创建的节点 | 刚加入队列时 |
| -1 | SIGNAL | 后续节点需要被唤醒 | 节点在等待队列中，需要前驱节点释放时唤醒 |
| 1 | CANCELLED | 节点已取消 | 线程被中断、超时或取消 |
| -2 | CONDITION | 在条件队列中 | 用于Condition的等待队列 |
| -3 | PROPAGATE | 共享模式传播 | 共享模式下，释放需要传播给后续节点 |

### 3.5 AQS 自旋逻辑详解

#### 3.5.0 自旋概念理解

**重要理解：AQS 自旋是指每个获取锁失败的线程都会进入自旋循环。**

**场景示例：T1、T2、T3 三个线程竞争同一把锁**

```mermaid
sequenceDiagram
    participant T1 as Thread1
    participant T2 as Thread2
    participant T3 as Thread3
    participant Lock as ReentrantLock
    participant AQS as AQS队列
    
    Note over T1: T1调用lock()
    T1->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 成功
    Note over AQS: state=1, owner=T1
    Note over T1: T1获取锁成功，执行临界区
    
    Note over T2: T2调用lock()
    T2->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 失败
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    Note over AQS: T2加入队列: [head] -> [T2]
    AQS->>AQS: acquireQueued(T2, 1)
    Note over T2: T2进入自旋循环
    
    loop T2的自旋循环
        AQS->>AQS: p = T2.predecessor() = head
        AQS->>AQS: p == head? true
        AQS->>AQS: tryAcquire(1) - 失败（T1仍持有）
        AQS->>AQS: shouldParkAfterFailedAcquire(head, T2)
        AQS->>AQS: 设置head.waitStatus = SIGNAL
        AQS-->>T2: 返回false，继续自旋
    end
    
    Note over T3: T3调用lock()
    T3->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 失败
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    Note over AQS: T3加入队列: [head] -> [T2] -> [T3]
    AQS->>AQS: acquireQueued(T3, 1)
    Note over T3: T3进入自旋循环
    
    loop T3的自旋循环
        AQS->>AQS: p = T3.predecessor() = T2
        AQS->>AQS: p == head? false
        AQS->>AQS: shouldParkAfterFailedAcquire(T2, T3)
        AQS->>AQS: 设置T2.waitStatus = SIGNAL
        AQS-->>T3: 返回false，继续自旋
    end
    
    Note over T2: 第二次自旋
    loop T2继续自旋
        AQS->>AQS: p = head, p == head? true
        AQS->>AQS: tryAcquire(1) - 失败
        AQS->>AQS: shouldParkAfterFailedAcquire(head, T2)
        Note over AQS: head.waitStatus = SIGNAL
        AQS-->>T2: 返回true，可以阻塞
        AQS->>T2: LockSupport.park() - 阻塞T2
    end
    
    Note over T3: 第二次自旋
    loop T3继续自旋
        AQS->>AQS: p = T2, p == head? false
        AQS->>AQS: shouldParkAfterFailedAcquire(T2, T3)
        Note over AQS: T2.waitStatus = SIGNAL
        AQS-->>T3: 返回true，可以阻塞
        AQS->>T3: LockSupport.park() - 阻塞T3
    end
    
    Note over T1: T1执行完毕
    T1->>Lock: unlock()
    Lock->>AQS: tryRelease(1) - state: 1->0
    AQS->>AQS: unparkSuccessor(head)
    AQS->>T2: 唤醒T2
    Note over T2: T2被唤醒，继续自旋
    T2->>AQS: tryAcquire(1) - 成功
    Note over T2: T2获取锁成功
```

**关键要点：**

1. **每个线程都有自己的自旋循环**：
   - T2 获取锁失败后，进入 `acquireQueued()` 方法，开始自己的自旋循环
   - T3 获取锁失败后，也进入 `acquireQueued()` 方法，开始自己的自旋循环
   - 每个线程的自旋是**独立的**，在自己的线程中执行

2. **自旋的内容不同**：
   - **T2（前驱是head）**：在自旋中会尝试 `tryAcquire()` 获取锁
   - **T3（前驱不是head）**：在自旋中不会尝试获取锁，只是设置前驱节点的 waitStatus

3. **自旋的目的**：
   - **减少阻塞开销**：在阻塞前进行有限次数的自旋，可能锁很快就被释放
   - **设置唤醒信号**：通过 `shouldParkAfterFailedAcquire` 设置前驱节点的 SIGNAL 状态
   - **判断是否需要阻塞**：只有确认前驱节点会唤醒自己时才阻塞

4. **自旋 vs 阻塞**：
   - **自旋阶段**：线程在 CPU 上运行，不断检查条件（消耗 CPU）
   - **阻塞阶段**：线程被 `LockSupport.park()` 阻塞，不消耗 CPU（等待被唤醒）

**自旋流程图（多线程视角）：**

```mermaid
graph TB
    subgraph "T1线程"
        T1Start[T1调用lock]
        T1Success[T1获取锁成功]
        T1Exec[T1执行临界区]
        T1Unlock[T1释放锁]
        
        T1Start --> T1Success
        T1Success --> T1Exec
        T1Exec --> T1Unlock
    end
    
    subgraph "T2线程 - 自旋循环"
        T2Start[T2调用lock - 失败]
        T2Spin1[T2第一次自旋<br/>p=head, tryAcquire失败]
        T2SetSignal1[设置head.waitStatus=SIGNAL]
        T2Spin2[T2第二次自旋<br/>p=head, tryAcquire失败]
        T2CheckSignal[检查head.waitStatus=SIGNAL]
        T2Block[T2阻塞 LockSupport.park]
        T2Wake[T2被唤醒]
        T2Spin3[T2继续自旋<br/>p=head, tryAcquire成功]
        T2Success[T2获取锁成功]
        
        T2Start --> T2Spin1
        T2Spin1 --> T2SetSignal1
        T2SetSignal1 --> T2Spin2
        T2Spin2 --> T2CheckSignal
        T2CheckSignal --> T2Block
        T2Block -.->|T1释放锁| T2Wake
        T2Wake --> T2Spin3
        T2Spin3 --> T2Success
    end
    
    subgraph "T3线程 - 自旋循环"
        T3Start[T3调用lock - 失败]
        T3Spin1[T3第一次自旋<br/>p=T2, p!=head]
        T3SetSignal1[设置T2.waitStatus=SIGNAL]
        T3Spin2[T3第二次自旋<br/>p=T2, p!=head]
        T3CheckSignal[检查T2.waitStatus=SIGNAL]
        T3Block[T3阻塞 LockSupport.park]
        T3Wake[T3被唤醒]
        T3Spin3[T3继续自旋<br/>p=T2, p!=head]
        T3Spin4[T3继续自旋<br/>p=head, tryAcquire成功]
        T3Success[T3获取锁成功]
        
        T3Start --> T3Spin1
        T3Spin1 --> T3SetSignal1
        T3SetSignal1 --> T3Spin2
        T3Spin2 --> T3CheckSignal
        T3CheckSignal --> T3Block
        T3Block -.->|T2释放锁| T3Wake
        T3Wake --> T3Spin3
        T3Spin3 --> T3Spin4
        T3Spin4 --> T3Success
    end
    
    T1Unlock --> T2Wake
    
    style T1Success fill:#c8e6c9
    style T2Success fill:#c8e6c9
    style T3Success fill:#c8e6c9
    style T2Block fill:#ffcdd2
    style T3Block fill:#ffcdd2
    style T2Spin1 fill:#fff9c4
    style T2Spin2 fill:#fff9c4
    style T3Spin1 fill:#fff9c4
    style T3Spin2 fill:#fff9c4
```

#### 3.5.1 acquireQueued() 方法核心逻辑

`acquireQueued()` 是 AQS 的核心自旋方法，负责在队列中自旋等待获取锁。

**每个线程都会执行自己的自旋循环：**

**重要理解：自旋中调用 `tryAcquire()` 时，state 不会一直 +1！**

**关键点：**
- 如果 `tryAcquire()` **失败**（锁仍被其他线程持有），state **不会变化**
- 只有 `tryAcquire()` **成功**（CAS成功）时，state 才会变为 1
- 只有**重入**时（当前线程已持有锁），state 才会 +1

**详细说明：**

当 T2 在自旋中调用 `NonfairSync#tryAcquire(1)` 时：

```java
final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();  // current = T2
    int c = getState();  // c = 1（T1持有锁）
    
    // 情况1：c == 0（锁未被占用）
    if (c == 0) {
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    // 情况2：锁已被占用，检查是否为重入
    else if (current == getExclusiveOwnerThread()) {
        // T2 != T1，不满足条件，不会执行这里
        int nextc = c + acquires;
        setState(nextc);
        return true;
    }
    // 情况3：锁被其他线程持有，返回false
    return false;  // T2会走到这里，返回false，state不变
}
```

**T2 自旋过程中的 state 变化：**

```mermaid
sequenceDiagram
    participant T1 as Thread1<br/>持有锁
    participant T2 as Thread2<br/>自旋中
    participant AQS as AQS<br/>state状态
    
    Note over AQS: 初始: state=1, owner=T1
    
    loop T2第一次自旋
        T2->>AQS: tryAcquire(1)
        AQS->>AQS: getState() = 1
        AQS->>AQS: current = T2, owner = T1
        AQS->>AQS: T2 != T1，不是重入
        AQS-->>T2: 返回false
        Note over AQS: state仍然=1，没有变化
    end
    
    loop T2第二次自旋
        T2->>AQS: tryAcquire(1)
        AQS->>AQS: getState() = 1
        AQS->>AQS: T2 != T1，不是重入
        AQS-->>T2: 返回false
        Note over AQS: state仍然=1，没有变化
    end
    
    Note over T1: T1执行完毕，释放锁
    T1->>AQS: unlock() - tryRelease(1)
    AQS->>AQS: state: 1 -> 0
    Note over AQS: state=0, owner=null
    
    loop T2第三次自旋（被唤醒后）
        T2->>AQS: tryAcquire(1)
        AQS->>AQS: getState() = 0
        AQS->>AQS: compareAndSetState(0, 1) - 成功
        AQS->>AQS: setExclusiveOwnerThread(T2)
        AQS-->>T2: 返回true
        Note over AQS: state=1, owner=T2（成功获取锁）
    end
```

**关键理解：**

1. **tryAcquire 失败时，state 不变**：
   - T2 调用 `tryAcquire(1)` 时，如果 state = 1 且 owner = T1
   - 因为 `T2 != T1`，不满足重入条件
   - 方法直接返回 `false`，**state 保持为 1，没有任何变化**

2. **tryAcquire 成功时，state 变为 1**：
   - 只有当 T1 释放锁后，state = 0
   - T2 再次调用 `tryAcquire(1)` 时，CAS 成功
   - state 从 0 变为 1，owner 设置为 T2

3. **只有重入时，state 才会 +1**：
   - 如果 T2 已经持有锁（owner = T2），再次调用 `tryAcquire(1)`
   - 会执行 `setState(c + 1)`，state 才会 +1
   - 但在等待队列中的线程不会出现这种情况

**完整示例：T2 自旋过程中的 state 值**

```
初始状态：
  state = 1, owner = T1

T2第一次自旋 - tryAcquire(1):
  getState() = 1
  current = T2, owner = T1
  T2 != T1，返回 false
  state = 1（不变）✓

T2第二次自旋 - tryAcquire(1):
  getState() = 1
  current = T2, owner = T1
  T2 != T1，返回 false
  state = 1（不变）✓

T1释放锁：
  tryRelease(1): state = 1 -> 0
  state = 0, owner = null

T2第三次自旋 - tryAcquire(1):
  getState() = 0
  compareAndSetState(0, 1) = true（成功）
  setExclusiveOwnerThread(T2)
  state = 1（从0变为1）✓
  owner = T2
```

**总结：**
- ❌ **错误理解**：每次调用 `tryAcquire()` 都会让 state +1
- ✅ **正确理解**：只有 `tryAcquire()` 成功（CAS成功）或重入时，state 才会变化
- ✅ **在等待队列中的线程**：每次 `tryAcquire()` 失败时，state 保持不变

```java
final boolean acquireQueued(final Node node, int arg) {
    boolean failed = true;
    try {
        boolean interrupted = false;
        // 自旋循环
        for (;;) {
            // 1. 获取前驱节点
            final Node p = node.predecessor();
            
            // 2. 如果前驱节点是head，说明当前节点是队列中第一个等待的节点
            //    尝试获取锁
            if (p == head && tryAcquire(arg)) {
                // 3. 获取成功，将当前节点设置为head
                setHead(node);
                p.next = null; // 帮助GC
                failed = false;
                return interrupted;
            }
            
            // 4. 获取失败，判断是否需要阻塞
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        // 5. 如果获取失败（异常或中断），取消节点
        if (failed)
            cancelAcquire(node);
    }
}
```

#### 3.5.2 自旋逻辑流程图

```mermaid
flowchart TD
    Start[acquireQueued开始]
    Loop[自旋循环 for]
    GetPred[获取前驱节点p]
    CheckHead{p == head?}
    TryAcquire[调用tryAcquire尝试获取锁]
    AcqResult{获取成功?}
    SetHead[设置当前节点为head<br/>p.next = null]
    Return[返回interrupted标志]
    ShouldPark[shouldParkAfterFailedAcquire<br/>判断是否需要阻塞]
    ParkResult{需要阻塞?}
    Park[parkAndCheckInterrupt<br/>阻塞线程]
    CheckInterrupt{被中断?}
    SetInterrupt[设置interrupted = true]
    Failed{获取失败?}
    Cancel[cancelAcquire<br/>取消节点]
    
    Start --> Loop
    Loop --> GetPred
    GetPred --> CheckHead
    CheckHead -->|是| TryAcquire
    CheckHead -->|否| ShouldPark
    TryAcquire --> AcqResult
    AcqResult -->|成功| SetHead
    AcqResult -->|失败| ShouldPark
    SetHead --> Return
    ShouldPark --> ParkResult
    ParkResult -->|是| Park
    ParkResult -->|否| Loop
    Park --> CheckInterrupt
    CheckInterrupt -->|是| SetInterrupt
    CheckInterrupt -->|否| Loop
    SetInterrupt --> Loop
    Loop -.->|异常或中断| Failed
    Failed -->|是| Cancel
    Cancel --> End[结束]
    Return --> End
    
    style Start fill:#e3f2fd
    style TryAcquire fill:#fff9c4
    style SetHead fill:#c8e6c9
    style Park fill:#ffcdd2
    style Cancel fill:#ffcdd2
```

#### 3.5.3 shouldParkAfterFailedAcquire() 方法详解

该方法负责判断在获取锁失败后，是否需要阻塞当前线程。

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    
    // 情况1：前驱节点的waitStatus是SIGNAL
    // 说明前驱节点释放锁时会唤醒当前节点，可以安全阻塞
    if (ws == Node.SIGNAL)
        return true;
    
    // 情况2：前驱节点的waitStatus > 0（CANCELLED）
    // 说明前驱节点已取消，需要跳过这些已取消的节点
    if (ws > 0) {
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } 
    // 情况3：前驱节点的waitStatus是0或PROPAGATE
    // 需要将前驱节点的waitStatus设置为SIGNAL
    else {
        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false; // 这次不阻塞，下次循环再检查
}
```

**shouldParkAfterFailedAcquire 逻辑图：**

```mermaid
flowchart TD
    Start[shouldParkAfterFailedAcquire]
    GetWS[获取前驱节点waitStatus]
    CheckWS{waitStatus值?}
    WS_SIGNAL[ws == SIGNAL<br/>返回true，可以阻塞]
    WS_CANCELLED[ws > 0 CANCELLED<br/>跳过已取消节点]
    WS_OTHER[ws == 0 或 PROPAGATE<br/>CAS设置为SIGNAL]
    SkipLoop[循环跳过已取消节点<br/>更新node.prev]
    UpdateNext[更新pred.next = node]
    CAS[compareAndSetWaitStatus<br/>pred, ws, SIGNAL]
    ReturnFalse[返回false<br/>不阻塞，继续自旋]
    ReturnTrue[返回true<br/>可以阻塞]
    
    Start --> GetWS
    GetWS --> CheckWS
    CheckWS -->|SIGNAL -1| WS_SIGNAL
    CheckWS -->|CANCELLED 1| WS_CANCELLED
    CheckWS -->|0 或 PROPAGATE| WS_OTHER
    WS_SIGNAL --> ReturnTrue
    WS_CANCELLED --> SkipLoop
    SkipLoop --> UpdateNext
    UpdateNext --> ReturnFalse
    WS_OTHER --> CAS
    CAS --> ReturnFalse
    
    style WS_SIGNAL fill:#c8e6c9
    style WS_CANCELLED fill:#ffcdd2
    style WS_OTHER fill:#fff9c4
```

#### 3.5.4 waitStatus = SIGNAL 的设置机制详解

**重要说明：waitStatus = SIGNAL 的设置与 `newCondition()` 方法无关！**

`newCondition()` 是用于创建 Condition 对象的，用于条件等待。而 waitStatus = SIGNAL 是在**同步队列（CLH队列）**中设置的，用于协调锁的获取和释放。

##### 3.5.4.1 SIGNAL 状态的设置时机

waitStatus = SIGNAL 是在 `shouldParkAfterFailedAcquire()` 方法中设置的，具体流程如下：

```mermaid
sequenceDiagram
    participant Thread as 线程
    participant AQS as AQS
    participant Node as 当前节点
    participant Pred as 前驱节点
    
    Note over Thread: 线程尝试获取锁失败，加入队列
    Thread->>AQS: acquireQueued(node, arg)
    
    loop 自旋循环
        AQS->>AQS: p = node.predecessor()
        AQS->>AQS: p == head && tryAcquire(arg)
        Note over AQS: tryAcquire失败
        
        AQS->>AQS: shouldParkAfterFailedAcquire(p, node)
        AQS->>Pred: 获取pred.waitStatus
        
        alt waitStatus == 0 (初始状态)
            Note over AQS: 第一次调用，前驱节点waitStatus=0
            AQS->>Pred: compareAndSetWaitStatus(pred, 0, SIGNAL)
            Note over Pred: waitStatus: 0 -> SIGNAL(-1)
            AQS-->>AQS: 返回false（不阻塞，继续自旋）
        else waitStatus == SIGNAL
            Note over AQS: 前驱节点已经是SIGNAL
            AQS-->>AQS: 返回true（可以阻塞）
        else waitStatus > 0 (CANCELLED)
            Note over AQS: 前驱节点已取消，跳过
            AQS->>AQS: 更新node.prev，跳过已取消节点
        end
    end
```

##### 3.5.4.2 设置 SIGNAL 的完整代码流程

```java
// 1. 线程获取锁失败，调用acquireQueued
final boolean acquireQueued(final Node node, int arg) {
    for (;;) {
        final Node p = node.predecessor();
        if (p == head && tryAcquire(arg)) {
            // 获取成功
            setHead(node);
            return false;
        }
        
        // 2. 获取失败，判断是否需要阻塞
        if (shouldParkAfterFailedAcquire(p, node) &&  // 这里会设置SIGNAL
            parkAndCheckInterrupt())
            interrupted = true;
    }
}

// 3. shouldParkAfterFailedAcquire 中设置 SIGNAL
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    
    if (ws == Node.SIGNAL)
        // 前驱节点已经是SIGNAL，可以安全阻塞
        return true;
    
    if (ws > 0) {
        // 前驱节点已取消，跳过
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } else {
        // 关键：这里设置前驱节点的waitStatus为SIGNAL
        // 使用CAS操作，确保原子性
        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false; // 返回false，继续自旋
}
```

##### 3.5.4.3 为什么需要设置 SIGNAL？

**SIGNAL 状态的作用：**

1. **信号机制**：告诉前驱节点"我在等待，你释放锁时请唤醒我"
2. **避免丢失唤醒**：确保前驱节点释放锁时知道需要唤醒后续节点
3. **阻塞安全**：只有前驱节点是 SIGNAL 时，当前节点才能安全阻塞

**设置 SIGNAL 的时机图：**

```mermaid
flowchart TD
    Start[线程获取锁失败]
    AddQueue[加入等待队列]
    FirstSpin[第一次自旋]
    CheckPred{前驱节点waitStatus?}
    WS0[waitStatus = 0<br/>初始状态]
    WSSignal[waitStatus = SIGNAL<br/>已设置]
    WSCancel[waitStatus = CANCELLED<br/>已取消]
    SetSignal[CAS设置前驱节点<br/>waitStatus = SIGNAL]
    ContinueSpin[继续自旋]
    SecondSpin[第二次自旋]
    CheckSignal{前驱节点<br/>waitStatus = SIGNAL?}
    CanBlock[可以安全阻塞]
    Park[LockSupport.park<br/>阻塞线程]
    SkipCancel[跳过已取消节点]
    
    Start --> AddQueue
    AddQueue --> FirstSpin
    FirstSpin --> CheckPred
    CheckPred -->|0| WS0
    CheckPred -->|SIGNAL| WSSignal
    CheckPred -->|CANCELLED| WSCancel
    WS0 --> SetSignal
    SetSignal --> ContinueSpin
    ContinueSpin --> SecondSpin
    SecondSpin --> CheckSignal
    CheckSignal -->|是| CanBlock
    CheckSignal -->|否| ContinueSpin
    CanBlock --> Park
    WSSignal --> CanBlock
    WSCancel --> SkipCancel
    SkipCancel --> ContinueSpin
    
    style SetSignal fill:#fff9c4
    style CanBlock fill:#c8e6c9
    style Park fill:#ffcdd2
```

##### 3.5.4.4 newCondition() 与 SIGNAL 的关系

**重要区别：**

| 特性 | waitStatus = SIGNAL | newCondition() |
|------|-------------------|----------------|
| **用途** | 同步队列（CLH队列）中的节点状态 | 创建条件等待队列 |
| **设置位置** | `shouldParkAfterFailedAcquire()` | `ReentrantLock.newCondition()` |
| **队列类型** | CLH同步队列 | Condition条件队列 |
| **节点状态** | SIGNAL (-1) | CONDITION (-2) |
| **使用场景** | 锁的获取和释放 | 条件等待（await/signal） |

**两种队列的对比：**

```mermaid
graph TB
    subgraph "同步队列（CLH队列）"
        CLH1[节点1<br/>waitStatus: SIGNAL]
        CLH2[节点2<br/>waitStatus: SIGNAL]
        CLH3[节点3<br/>waitStatus: 0]
    end
    
    subgraph "条件队列（Condition队列）"
        COND1[节点A<br/>waitStatus: CONDITION]
        COND2[节点B<br/>waitStatus: CONDITION]
    end
    
    Lock[ReentrantLock]
    Condition[Condition对象]
    
    Lock -->|lock/unlock| CLH1
    Lock -->|newCondition| Condition
    Condition -->|await/signal| COND1
    
    Note1["SIGNAL在同步队列中设置<br/>用于锁的获取和释放"]
    Note2["CONDITION在条件队列中设置<br/>用于条件等待"]
    
    style CLH1 fill:#c8e6c9
    style CLH2 fill:#c8e6c9
    style COND1 fill:#fff9c4
    style COND2 fill:#fff9c4
```

##### 3.5.4.5 完整示例：SIGNAL 的设置过程

```java
public class SignalStatusExample {
    private ReentrantLock lock = new ReentrantLock();
    
    public void example() {
        // Thread1 获取锁
        Thread t1 = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("Thread1 持有锁");
                Thread.sleep(2000); // 模拟长时间持有
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        });
        t1.start();
        
        // Thread2 尝试获取锁（会失败，加入队列）
        Thread t2 = new Thread(() -> {
            lock.lock(); // 这里会触发SIGNAL的设置
            try {
                System.out.println("Thread2 获取到锁");
            } finally {
                lock.unlock();
            }
        });
        t2.start();
        
        /*
         * 执行流程：
         * 1. Thread1 获取锁成功，state=1, owner=Thread1
         * 2. Thread2 调用lock()，CAS失败
         * 3. Thread2 调用acquire(1)
         * 4. tryAcquire(1) 失败（Thread1仍持有）
         * 5. addWaiter(Node.EXCLUSIVE) - 创建节点，waitStatus=0
         * 6. acquireQueued(node, 1) 开始自旋
         * 7. 第一次自旋：
         *    - p = head, p == head? true
         *    - tryAcquire(1) 失败
         *    - shouldParkAfterFailedAcquire(head, node)
         *    - head.waitStatus = 0
         *    - compareAndSetWaitStatus(head, 0, SIGNAL) ✓
         *    - 返回false，继续自旋
         * 8. 第二次自旋：
         *    - p = head, p == head? true
         *    - tryAcquire(1) 失败
         *    - shouldParkAfterFailedAcquire(head, node)
         *    - head.waitStatus = SIGNAL
         *    - 返回true，可以阻塞
         * 9. parkAndCheckInterrupt() - Thread2被阻塞
         * 10. Thread1释放锁，唤醒Thread2
         * 11. Thread2继续自旋，获取锁成功
         */
    }
}
```

##### 3.5.4.6 关键要点总结

1. **SIGNAL 的设置时机**：
   - 在 `shouldParkAfterFailedAcquire()` 方法中设置
   - 使用 `compareAndSetWaitStatus()` CAS操作
   - 设置的是**前驱节点**的 waitStatus

2. **SIGNAL 的作用**：
   - 表示"后续节点在等待，释放锁时请唤醒"
   - 确保阻塞前，前驱节点知道需要唤醒后续节点
   - 避免丢失唤醒信号

3. **与 newCondition() 的区别**：
   - `newCondition()` 创建的是**条件队列**，节点 waitStatus = CONDITION (-2)
   - SIGNAL 是在**同步队列**中设置的，节点 waitStatus = SIGNAL (-1)
   - 两者是完全不同的机制

4. **设置流程**：
   ```
   获取锁失败 → 加入队列 → 自旋循环 → shouldParkAfterFailedAcquire 
   → 检查前驱节点waitStatus → 如果是0，CAS设置为SIGNAL 
   → 继续自旋 → 再次检查 → 如果是SIGNAL，可以阻塞
   ```

#### 3.5.5 parkAndCheckInterrupt() 方法

```java
private final boolean parkAndCheckInterrupt() {
    // 阻塞当前线程
    LockSupport.park(this);
    // 返回线程是否被中断（会清除中断标志）
    return Thread.interrupted();
}
```

#### 3.5.5 完整自旋过程示例

**场景：3个线程竞争同一把锁**

```mermaid
sequenceDiagram
    participant T1 as Thread1<br/>持有锁
    participant T2 as Thread2<br/>等待中
    participant T3 as Thread3<br/>等待中
    participant AQS as AQS队列
    
    Note over T1: state=1, owner=T1
    
    T2->>AQS: lock() - CAS失败
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    Note over AQS: 队列: [head] -> [T2]
    AQS->>AQS: acquireQueued(T2, 1)
    
    loop 自旋循环1
        AQS->>AQS: p = T2.predecessor() = head
        AQS->>AQS: p == head? true
        AQS->>AQS: tryAcquire(1) - 失败（T1仍持有）
        AQS->>AQS: shouldParkAfterFailedAcquire(head, T2)
        Note over AQS: head.waitStatus = 0
        AQS->>AQS: CAS设置head.waitStatus = SIGNAL
        AQS-->>AQS: 返回false（不阻塞）
    end
    
    loop 自旋循环2
        AQS->>AQS: p = head, p == head? true
        AQS->>AQS: tryAcquire(1) - 失败
        AQS->>AQS: shouldParkAfterFailedAcquire(head, T2)
        Note over AQS: head.waitStatus = SIGNAL
        AQS-->>AQS: 返回true（可以阻塞）
        AQS->>T2: LockSupport.park() - 阻塞T2
    end
    
    T3->>AQS: lock() - CAS失败
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    Note over AQS: 队列: [head] -> [T2] -> [T3]
    AQS->>AQS: acquireQueued(T3, 1)
    
    loop 自旋循环3
        AQS->>AQS: p = T3.predecessor() = T2
        AQS->>AQS: p == head? false
        AQS->>AQS: shouldParkAfterFailedAcquire(T2, T3)
        Note over AQS: T2.waitStatus = 0
        AQS->>AQS: CAS设置T2.waitStatus = SIGNAL
        AQS-->>AQS: 返回false
    end
    
    loop 自旋循环4
        AQS->>AQS: p = T2, p == head? false
        AQS->>AQS: shouldParkAfterFailedAcquire(T2, T3)
        Note over AQS: T2.waitStatus = SIGNAL
        AQS-->>AQS: 返回true
        AQS->>T3: LockSupport.park() - 阻塞T3
    end
    
    Note over T1: 执行完毕
    T1->>AQS: unlock() - 释放锁
    AQS->>AQS: tryRelease(1) - state: 1->0
    AQS->>AQS: unparkSuccessor(head)
    AQS->>T2: LockSupport.unpark(T2) - 唤醒T2
    Note over T2: 被唤醒，继续自旋
    AQS->>AQS: acquireQueued继续执行
    AQS->>AQS: p = head, p == head? true
    AQS->>AQS: tryAcquire(1) - 成功
    AQS->>AQS: setHead(T2), T2成为新head
    Note over AQS: 队列: [head=T2] -> [T3]
    AQS-->>T2: 获取锁成功
```

#### 3.5.6 数据结构变化示例

**初始状态：**
```
AQS:
  state = 1
  head = [虚节点, waitStatus=0]
  tail = [虚节点]
  owner = Thread1
```

**Thread2 尝试获取锁失败，加入队列：**
```
AQS:
  state = 1
  head = [虚节点, waitStatus=0]
  tail = [T2节点, waitStatus=0, thread=Thread2]
  owner = Thread1
  
队列结构:
  [head] <-> [T2] <-> [tail]
```

**Thread2 第一次自旋：**
```
1. p = T2.predecessor() = head
2. p == head? true
3. tryAcquire(1) -> false（Thread1仍持有）
4. shouldParkAfterFailedAcquire(head, T2):
   - head.waitStatus = 0
   - CAS设置 head.waitStatus = SIGNAL
   - 返回false（不阻塞，继续自旋）

队列结构:
  [head, waitStatus=SIGNAL] <-> [T2, waitStatus=0] <-> [tail]
```

**Thread2 第二次自旋：**
```
1. p = head, p == head? true
2. tryAcquire(1) -> false
3. shouldParkAfterFailedAcquire(head, T2):
   - head.waitStatus = SIGNAL
   - 返回true（可以阻塞）
4. parkAndCheckInterrupt() -> 阻塞Thread2

队列结构:
  [head, waitStatus=SIGNAL] <-> [T2, waitStatus=0, 阻塞] <-> [tail]
```

**Thread3 加入队列：**
```
AQS:
  state = 1
  head = [虚节点, waitStatus=SIGNAL]
  tail = [T3节点, waitStatus=0, thread=Thread3]
  owner = Thread1
  
队列结构:
  [head, waitStatus=SIGNAL] <-> [T2, waitStatus=0, 阻塞] <-> [T3, waitStatus=0] <-> [tail]
```

**Thread1 释放锁，唤醒Thread2：**
```
1. unlock() -> tryRelease(1) -> state: 1->0
2. unparkSuccessor(head) -> LockSupport.unpark(Thread2)
3. Thread2被唤醒，继续自旋：
   - p = head, p == head? true
   - tryAcquire(1) -> 成功（state=0，CAS成功）
   - setHead(T2)，T2成为新head
   - head.next = null（原head断开连接）

队列结构:
  [head=T2, waitStatus=0] <-> [T3, waitStatus=0] <-> [tail]
  
AQS:
  state = 1
  owner = Thread2
```

#### 3.5.7 自旋优化的关键点

1. **减少不必要的阻塞**：
   - 在阻塞前会进行多次自旋尝试
   - 只有确认前驱节点会唤醒自己时才阻塞

2. **清理已取消的节点**：
   - `shouldParkAfterFailedAcquire` 会跳过 CANCELLED 节点
   - 保持队列的完整性

3. **公平性保证**：
   - 只有前驱节点是 head 时才尝试获取锁
   - 确保 FIFO 顺序

4. **中断处理**：
   - `parkAndCheckInterrupt` 会检查中断标志
   - 中断不会立即退出，而是设置标志，在获取锁后处理

#### 3.5.8 自旋 vs 阻塞的权衡

```mermaid
graph TB
    Start[线程尝试获取锁]
    TryAcquire[tryAcquire尝试]
    Success{成功?}
    GetLock[获取锁成功]
    Failed[获取失败]
    CheckPred{前驱是head?}
    Spin[自旋尝试]
    SpinResult{成功?}
    CheckPark[shouldParkAfterFailedAcquire]
    ParkResult{需要阻塞?}
    Block[LockSupport.park<br/>阻塞线程]
    WakeUp[被唤醒]
    
    Start --> TryAcquire
    TryAcquire --> Success
    Success -->|是| GetLock
    Success -->|否| Failed
    Failed --> CheckPred
    CheckPred -->|是| Spin
    CheckPred -->|否| CheckPark
    Spin --> SpinResult
    SpinResult -->|成功| GetLock
    SpinResult -->|失败| CheckPark
    CheckPark --> ParkResult
    ParkResult -->|是| Block
    ParkResult -->|否| TryAcquire
    Block --> WakeUp
    WakeUp --> TryAcquire
    
    style GetLock fill:#c8e6c9
    style Block fill:#ffcdd2
    style Spin fill:#fff9c4
```

**自旋的优势：**
- 避免线程切换开销（上下文切换成本高）
- 锁持有时间短时，自旋比阻塞更高效
- 减少系统调用（park/unpark）

**自旋的劣势：**
- 消耗CPU资源（空转）
- 高竞争场景下效率低

**AQS的优化策略：**
- 有限次数的自旋（通过shouldParkAfterFailedAcquire控制）
- 只有前驱节点是head时才自旋（减少无效尝试）
- 及时阻塞，避免CPU浪费

## 4. 锁获取流程

### 4.1 非公平锁获取流程（lock()）

```mermaid
sequenceDiagram
    participant Thread as 线程
    participant ReentrantLock as ReentrantLock
    participant NonfairSync as NonfairSync
    participant AQS as AQS
    participant CLH as CLH队列
    
    Thread->>ReentrantLock: lock()
    ReentrantLock->>NonfairSync: lock()
    
    Note over NonfairSync: 1. 尝试CAS直接获取锁
    NonfairSync->>AQS: compareAndSetState(0, 1)
    
    alt CAS成功（锁未被占用）
        AQS-->>NonfairSync: true
        NonfairSync->>AQS: setExclusiveOwnerThread(Thread)
        NonfairSync-->>ReentrantLock: 获取成功
        ReentrantLock-->>Thread: 返回，继续执行
    else CAS失败（锁被占用）
        AQS-->>NonfairSync: false
        NonfairSync->>AQS: acquire(1)
        AQS->>NonfairSync: tryAcquire(1)
        
        alt 重入检查（当前线程已持有锁）
            NonfairSync->>AQS: getState()
            AQS-->>NonfairSync: state > 0
            NonfairSync->>AQS: getExclusiveOwnerThread()
            AQS-->>NonfairSync: currentThread
            NonfairSync->>AQS: setState(state + 1)
            NonfairSync-->>AQS: true
            AQS-->>ReentrantLock: 重入成功
            ReentrantLock-->>Thread: 返回，继续执行
        else 获取失败，进入队列
            NonfairSync-->>AQS: false
            AQS->>AQS: addWaiter(Node.EXCLUSIVE)
            AQS->>CLH: 将线程加入等待队列尾部
            AQS->>AQS: acquireQueued(node, 1)
            
            loop 自旋等待
                AQS->>CLH: 检查是否轮到当前节点
                CLH-->>AQS: 否
                AQS->>AQS: shouldParkAfterFailedAcquire()
                AQS->>AQS: parkAndCheckInterrupt()
                Note over AQS: 线程被阻塞（LockSupport.park）
            end
            
            Note over CLH: 前置节点释放锁
            AQS->>AQS: 被唤醒
            AQS->>NonfairSync: tryAcquire(1)
            NonfairSync-->>AQS: true
            AQS-->>ReentrantLock: 获取成功
            ReentrantLock-->>Thread: 返回，继续执行
        end
    end
```

### 4.2 公平锁获取流程

```mermaid
sequenceDiagram
    participant Thread as 线程
    participant ReentrantLock as ReentrantLock
    participant FairSync as FairSync
    participant AQS as AQS
    participant CLH as CLH队列
    
    Thread->>ReentrantLock: lock()
    ReentrantLock->>FairSync: lock()
    FairSync->>AQS: acquire(1)
    AQS->>FairSync: tryAcquire(1)
    
    Note over FairSync: 1. 检查队列是否有等待节点
    FairSync->>AQS: hasQueuedPredecessors()
    
    alt 队列为空或当前节点是队首
        FairSync->>AQS: getState()
        alt state == 0 (锁未被占用)
            FairSync->>AQS: compareAndSetState(0, 1)
            AQS-->>FairSync: true
            FairSync->>AQS: setExclusiveOwnerThread(Thread)
            FairSync-->>AQS: true
            AQS-->>ReentrantLock: 获取成功
            ReentrantLock-->>Thread: 返回
        else state > 0 且当前线程已持有
            FairSync->>AQS: 重入逻辑（同非公平锁）
            FairSync-->>AQS: true
            AQS-->>ReentrantLock: 重入成功
        else 锁被其他线程持有
            FairSync-->>AQS: false
        end
    else 队列有其他节点等待
        FairSync-->>AQS: false
        Note over FairSync: 公平锁：不插队，直接返回false
    end
    
    alt tryAcquire失败
        AQS->>AQS: addWaiter(Node.EXCLUSIVE)
        AQS->>CLH: 加入等待队列尾部
        AQS->>AQS: acquireQueued(node, 1)
        Note over AQS: 进入队列等待（同非公平锁）
    end
```

### 4.3 公平锁 vs 非公平锁对比

```mermaid
graph TB
    Start[线程尝试获取锁]
    
    Start --> Fair{公平锁?}
    
    Fair -->|是| FairCheck[检查队列是否有等待节点]
    FairCheck --> FairQueue{队列有节点?}
    FairQueue -->|是| FairEnqueue[直接加入队列等待]
    FairQueue -->|否| FairTry[尝试CAS获取锁]
    
    Fair -->|否| NonFairTry[立即尝试CAS获取锁]
    NonFairTry --> NonFairResult{CAS成功?}
    NonFairResult -->|是| Success[获取成功]
    NonFairResult -->|否| NonFairEnqueue[加入队列等待]
    
    FairTry --> FairResult{CAS成功?}
    FairResult -->|是| Success
    FairResult -->|否| FairEnqueue
    
    FairEnqueue --> Wait[等待唤醒]
    NonFairEnqueue --> Wait
    Wait --> Success
    
    style Start fill:#e3f2fd
    style Success fill:#c8e6c9
    style Wait fill:#fff9c4
```

### 4.4 tryLock() 非阻塞获取

```mermaid
flowchart TD
    Start[tryLock]
    CheckState[检查state状态]
    StateZero{state == 0?}
    TryCAS[尝试CAS获取]
    CASResult{CAS成功?}
    SetOwner[设置独占线程]
    CheckReentrant[检查重入]
    Reentrant{当前线程<br/>已持有?}
    IncState[state++]
    Success[返回true]
    Fail[返回false]
    
    Start --> CheckState
    CheckState --> StateZero
    StateZero -->|是| TryCAS
    StateZero -->|否| CheckReentrant
    TryCAS --> CASResult
    CASResult -->|成功| SetOwner
    CASResult -->|失败| CheckReentrant
    SetOwner --> Success
    CheckReentrant --> Reentrant
    Reentrant -->|是| IncState
    Reentrant -->|否| Fail
    IncState --> Success
    
    style Success fill:#c8e6c9
    style Fail fill:#ffcdd2
```

### 4.5 多个方法使用同一把锁的机制详解

#### 4.5.1 核心问题：如何知道是哪个方法在等待？

**重要理解：ReentrantLock 本身不知道是哪个方法调用的 `lock()`，它只关心：**
- 哪个线程持有锁（`exclusiveOwnerThread`）
- 等待队列中的线程（CLH队列中的节点）

**锁的粒度是 ReentrantLock 实例级别，不是方法级别。**

#### 4.5.2 锁的作用域设计原理

```java
public class SharedResource {
    // 同一个锁实例保护整个共享资源
    private final ReentrantLock lock = new ReentrantLock();
    private int counter = 0;
    private String data = "";
    
    // 方法1：使用锁保护counter
    public void increment() {
        lock.lock();  // 获取锁
        try {
            counter++;  // 临界区1
        } finally {
            lock.unlock();  // 释放锁
        }
    }
    
    // 方法2：使用同一个锁保护data
    public void updateData(String newData) {
        lock.lock();  // 获取同一个锁
        try {
            data = newData;  // 临界区2
        } finally {
            lock.unlock();  // 释放锁
        }
    }
    
    // 方法3：使用同一个锁保护复合操作
    public void incrementAndUpdate(String newData) {
        lock.lock();  // 获取同一个锁
        try {
            counter++;      // 临界区3
            data = newData;
        } finally {
            lock.unlock();  // 释放锁
        }
    }
}
```

**设计原理图：**

```mermaid
graph TB
    subgraph "SharedResource类"
        Lock[ReentrantLock实例<br/>lock]
        Method1[increment方法<br/>lock.lock/unlock]
        Method2[updateData方法<br/>lock.lock/unlock]
        Method3[incrementAndUpdate方法<br/>lock.lock/unlock]
        Resource[共享资源<br/>counter, data]
    end
    
    Lock --> Method1
    Lock --> Method2
    Lock --> Method3
    Method1 --> Resource
    Method2 --> Resource
    Method3 --> Resource
    
    style Lock fill:#ffebee
    style Resource fill:#e3f2fd
```

#### 4.5.3 多线程竞争同一把锁的完整流程

**场景：3个线程，分别调用不同的方法**

```mermaid
sequenceDiagram
    participant T1 as Thread1<br/>调用increment
    participant T2 as Thread2<br/>调用updateData
    participant T3 as Thread3<br/>调用incrementAndUpdate
    participant Lock as ReentrantLock<br/>同一个实例
    participant AQS as AQS队列
    
    Note over T1: T1调用increment()
    T1->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 成功
    Note over AQS: state=1, owner=T1
    T1->>T1: counter++ (执行临界区)
    
    Note over T2: T2调用updateData()
    T2->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 失败
    Note over AQS: T1持有锁，T2加入队列
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    AQS->>AQS: acquireQueued(T2, 1)
    Note over T2: T2被阻塞，等待T1释放锁
    
    Note over T3: T3调用incrementAndUpdate()
    T3->>Lock: lock()
    Lock->>AQS: tryAcquire(1) - 失败
    Note over AQS: T1仍持有锁，T3加入队列
    AQS->>AQS: addWaiter(Node.EXCLUSIVE)
    AQS->>AQS: acquireQueued(T3, 1)
    Note over T3: T3被阻塞，等待T1释放锁
    
    Note over AQS: 队列状态: [head] -> [T2] -> [T3]
    
    T1->>T1: 执行完毕
    T1->>Lock: unlock()
    Lock->>AQS: tryRelease(1) - state: 1->0
    AQS->>AQS: unparkSuccessor(head)
    AQS->>T2: 唤醒T2
    Note over T2: T2被唤醒，继续自旋
    T2->>AQS: tryAcquire(1) - 成功
    Note over AQS: state=1, owner=T2
    T2->>T2: data = newData (执行临界区)
    T2->>Lock: unlock()
    Lock->>AQS: tryRelease(1) - state: 1->0
    AQS->>AQS: unparkSuccessor(head)
    AQS->>T3: 唤醒T3
    Note over T3: T3被唤醒，继续自旋
    T3->>AQS: tryAcquire(1) - 成功
    Note over AQS: state=1, owner=T3
    T3->>T3: counter++, data = newData (执行临界区)
    T3->>Lock: unlock()
```

#### 4.5.4 关键理解：锁不区分调用位置

**ReentrantLock 的内部状态：**

```java
// ReentrantLock 只维护这些信息：
public class ReentrantLock {
    private final Sync sync;
    
    // Sync 内部（继承自AQS）：
    private volatile int state;              // 锁状态：0=未锁定，>0=锁定次数
    private transient volatile Node head;    // 等待队列头节点
    private transient volatile Node tail;    // 等待队列尾节点
    private transient Thread exclusiveOwnerThread;  // 持有锁的线程
    
    // Node 节点中：
    volatile Thread thread;  // 等待的线程（不记录是哪个方法）
    volatile int waitStatus; // 等待状态
}
```

**重要点：**
1. **不记录调用位置**：ReentrantLock 不知道是 `increment()` 还是 `updateData()` 调用的 `lock()`
2. **只记录线程**：等待队列中的 Node 只存储 `Thread` 对象，不存储方法信息
3. **锁的粒度**：整个 `ReentrantLock` 实例是一个锁，所有调用 `lock()` 的地方都竞争同一个锁

#### 4.5.5 实际应用场景

**场景1：保护共享资源的不同操作**

```java
public class BankAccount {
    private final ReentrantLock lock = new ReentrantLock();
    private double balance = 1000.0;
    
    // 存款操作
    public void deposit(double amount) {
        lock.lock();
        try {
            balance += amount;  // 临界区1
        } finally {
            lock.unlock();
        }
    }
    
    // 取款操作
    public void withdraw(double amount) {
        lock.lock();  // 同一个锁
        try {
            if (balance >= amount) {
                balance -= amount;  // 临界区2
            }
        } finally {
            lock.unlock();
        }
    }
    
    // 查询余额
    public double getBalance() {
        lock.lock();  // 同一个锁
        try {
            return balance;  // 临界区3
        } finally {
            lock.unlock();
        }
    }
}
```

**执行流程：**
- Thread1 调用 `deposit(100)` → 获取锁 → 执行
- Thread2 调用 `withdraw(50)` → 尝试获取锁 → 失败 → 进入队列等待
- Thread3 调用 `getBalance()` → 尝试获取锁 → 失败 → 进入队列等待
- Thread1 释放锁 → Thread2 被唤醒 → 执行 `withdraw`
- Thread2 释放锁 → Thread3 被唤醒 → 执行 `getBalance`

**场景2：保护复合操作**

```java
public class Counter {
    private final ReentrantLock lock = new ReentrantLock();
    private int count = 0;
    
    public void increment() {
        lock.lock();
        try {
            count++;  // 简单操作
        } finally {
            lock.unlock();
        }
    }
    
    public void incrementBy(int n) {
        lock.lock();  // 同一个锁
        try {
            for (int i = 0; i < n; i++) {
                count++;  // 复合操作
            }
        } finally {
            lock.unlock();
        }
    }
}
```

#### 4.5.6 如何理解"其他线程在这个地方等待"

**虽然 ReentrantLock 不知道是哪个方法，但我们可以通过设计来理解：**

```mermaid
graph TB
    subgraph "线程视角"
        T1[Thread1: 在increment中持有锁]
        T2[Thread2: 在updateData中等待锁]
        T3[Thread3: 在incrementAndUpdate中等待锁]
    end
    
    subgraph "ReentrantLock视角"
        Lock[同一个ReentrantLock实例]
        State[state=1<br/>owner=Thread1]
        Queue[等待队列<br/>Thread2, Thread3]
    end
    
    subgraph "实际执行"
        Exec1[Thread1执行counter++]
        Wait2[Thread2阻塞在lock调用处]
        Wait3[Thread3阻塞在lock调用处]
    end
    
    T1 --> Lock
    T2 --> Lock
    T3 --> Lock
    Lock --> State
    Lock --> Queue
    T1 --> Exec1
    T2 --> Wait2
    T3 --> Wait3
    
    style T1 fill:#c8e6c9
    style T2 fill:#fff9c4
    style T3 fill:#fff9c4
    style Lock fill:#ffebee
```

**关键理解：**

1. **从代码层面**：
   - Thread2 阻塞在 `updateData()` 方法的 `lock.lock()` 调用处
   - Thread3 阻塞在 `incrementAndUpdate()` 方法的 `lock.lock()` 调用处
   - 虽然 ReentrantLock 不知道具体方法，但线程确实在对应的方法中等待

2. **从锁的视角**：
   - ReentrantLock 只看到：Thread1 持有锁，Thread2 和 Thread3 在等待队列中
   - 不关心它们是从哪个方法调用的

3. **从设计角度**：
   - 同一个锁实例保护的是同一个共享资源
   - 多个方法使用同一把锁，意味着它们访问的是同一个共享资源
   - 这是**锁粒度设计**的体现

#### 4.5.7 锁粒度设计原则

**原则1：一个共享资源 = 一把锁**

```java
// ✅ 正确：一个资源，一把锁
public class GoodDesign {
    private final ReentrantLock lock = new ReentrantLock();
    private int resource1 = 0;
    
    public void method1() { lock.lock(); try { resource1++; } finally { lock.unlock(); } }
    public void method2() { lock.lock(); try { resource1--; } finally { lock.unlock(); } }
}

// ❌ 错误：一个资源，多把锁（无法保护）
public class BadDesign {
    private final ReentrantLock lock1 = new ReentrantLock();
    private final ReentrantLock lock2 = new ReentrantLock();
    private int resource1 = 0;
    
    public void method1() { lock1.lock(); try { resource1++; } finally { lock1.unlock(); } }
    public void method2() { lock2.lock(); try { resource1--; } finally { lock2.unlock(); } }
    // 问题：method1和method2可以同时执行，无法保护resource1
}
```

**原则2：不同资源可以使用不同锁**

```java
// ✅ 正确：不同资源，不同锁（提高并发性）
public class GoodDesign {
    private final ReentrantLock lock1 = new ReentrantLock();
    private final ReentrantLock lock2 = new ReentrantLock();
    private int resource1 = 0;
    private String resource2 = "";
    
    public void updateResource1() { 
        lock1.lock(); try { resource1++; } finally { lock1.unlock(); } 
    }
    
    public void updateResource2() { 
        lock2.lock(); try { resource2 = "new"; } finally { lock2.unlock(); } 
    }
    // 优点：两个方法可以并发执行，互不影响
}
```

#### 4.5.8 调试和监控：如何知道是哪个方法在等待

虽然 ReentrantLock 不记录方法信息，但可以通过以下方式了解：

**方法1：使用线程堆栈**

```java
// 当线程阻塞时，可以通过线程堆栈看到调用位置
Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
    if (thread.getState() == Thread.State.WAITING) {
        System.out.println("Thread: " + thread.getName());
        for (StackTraceElement element : stackTrace) {
            System.out.println("  " + element);
            // 可以看到是哪个类的哪个方法调用了lock()
        }
    }
});
```

**方法2：添加日志**

```java
public class SharedResource {
    private final ReentrantLock lock = new ReentrantLock();
    
    public void increment() {
        System.out.println(Thread.currentThread().getName() + " 尝试在increment中获取锁");
        lock.lock();
        try {
            System.out.println(Thread.currentThread().getName() + " 在increment中获取到锁");
            // 临界区代码
        } finally {
            System.out.println(Thread.currentThread().getName() + " 在increment中释放锁");
            lock.unlock();
        }
    }
    
    public void updateData(String data) {
        System.out.println(Thread.currentThread().getName() + " 尝试在updateData中获取锁");
        lock.lock();
        try {
            System.out.println(Thread.currentThread().getName() + " 在updateData中获取到锁");
            // 临界区代码
        } finally {
            System.out.println(Thread.currentThread().getName() + " 在updateData中释放锁");
            lock.unlock();
        }
    }
}
```

**方法3：使用工具类包装**

```java
public class TraceableReentrantLock extends ReentrantLock {
    public void lockWithTrace(String methodName) {
        System.out.println(Thread.currentThread().getName() + 
                          " 在 " + methodName + " 中尝试获取锁");
        lock();
        System.out.println(Thread.currentThread().getName() + 
                          " 在 " + methodName + " 中获取到锁");
    }
    
    public void unlockWithTrace(String methodName) {
        System.out.println(Thread.currentThread().getName() + 
                          " 在 " + methodName + " 中释放锁");
        unlock();
    }
}
```

#### 4.5.9 总结

**核心要点：**

1. **ReentrantLock 不区分调用位置**：它只关心哪个线程持有锁，哪些线程在等待
2. **锁的粒度是实例级别**：同一个 `ReentrantLock` 实例的所有 `lock()` 调用都竞争同一把锁
3. **设计原则**：一个共享资源使用一把锁，多个方法可以共享同一把锁来保护同一个资源
4. **等待位置**：虽然锁不知道方法名，但线程确实阻塞在对应方法的 `lock()` 调用处
5. **调试方法**：可以通过线程堆栈、日志或工具类来追踪是哪个方法在等待

**设计建议：**
- 明确锁保护的对象（共享资源）
- 在类级别定义锁，多个方法共享
- 使用清晰的命名和注释说明锁的作用域
- 避免过度使用锁，只在必要时使用

## 5. 锁释放流程

### 5.1 unlock() 释放流程

```mermaid
sequenceDiagram
    participant Thread as 线程
    participant ReentrantLock as ReentrantLock
    participant Sync as Sync
    participant AQS as AQS
    participant CLH as CLH队列
    
    Thread->>ReentrantLock: unlock()
    ReentrantLock->>Sync: release(1)
    Sync->>AQS: release(1)
    AQS->>Sync: tryRelease(1)
    
    Note over Sync: 1. 检查是否为持有锁的线程
    Sync->>AQS: getExclusiveOwnerThread()
    AQS-->>Sync: currentThread
    
    alt 不是持有锁的线程
        Sync-->>AQS: throw IllegalMonitorStateException
    else 是持有锁的线程
        Sync->>AQS: getState()
        AQS-->>Sync: state = 2 (重入了2次)
        Sync->>AQS: c = state - releases (2-1=1)
        
        alt c == 0 (完全释放)
            Sync->>AQS: setExclusiveOwnerThread(null)
            Sync->>AQS: setState(0)
            Sync-->>AQS: true
        else c > 0 (部分释放，仍有重入)
            Sync->>AQS: setState(c)
            Sync-->>AQS: false
        end
        
        alt tryRelease返回true（完全释放）
            AQS->>AQS: h = head
            AQS->>CLH: 获取队列头节点
            
            alt 头节点存在且waitStatus != 0
                AQS->>CLH: 找到需要唤醒的后继节点
                AQS->>CLH: unparkSuccessor(h)
                Note over CLH: LockSupport.unpark(后继节点线程)
                CLH-->>Thread: 唤醒等待线程
                Note over Thread: 等待线程继续尝试获取锁
            end
        end
    end
```

### 5.2 锁状态转换图

```mermaid
stateDiagram-v2
    [*] --> 未锁定: 初始化 state=0
    
    未锁定 --> 已锁定: lock() state=1
    未锁定 --> 已锁定: tryLock()成功
    
    已锁定 --> 重入: 同一线程再次lock() state=2
    已锁定 --> 已锁定: 其他线程lock() 进入队列等待
    
    重入 --> 重入: 继续重入 state=n
    重入 --> 部分释放: unlock() state=n-1
    
    部分释放 --> 重入: 同一线程再次lock() state=n
    部分释放 --> 已锁定: unlock() state=1
    部分释放 --> 未锁定: unlock()到0 state=0
    
    已锁定 --> 未锁定: unlock() state=0
    未锁定 --> [*]: 对象销毁
```

### 5.3 重入锁的完整释放机制详解

**关键点：每次 `unlock()` 调用只会将 state 减 1，需要调用相同次数的 `unlock()` 才能完全释放锁。**

#### 5.3.1 重入锁释放流程示例

假设线程 Thread1 获取了锁 3 次（state = 3），释放过程如下：

```mermaid
sequenceDiagram
    participant T1 as Thread1
    participant Lock as ReentrantLock
    participant AQS as AQS
    
    Note over T1,AQS: 初始状态：state=3（已重入2次）
    
    T1->>Lock: unlock() - 第1次释放
    Lock->>AQS: tryRelease(1)
    AQS->>AQS: c = state - 1 = 3 - 1 = 2
    Note over AQS: c = 2 > 0，部分释放
    AQS->>AQS: setState(2)
    AQS-->>Lock: return false（未完全释放）
    Note over T1: state=2，锁仍被持有
    
    T1->>Lock: unlock() - 第2次释放
    Lock->>AQS: tryRelease(1)
    AQS->>AQS: c = state - 1 = 2 - 1 = 1
    Note over AQS: c = 1 > 0，部分释放
    AQS->>AQS: setState(1)
    AQS-->>Lock: return false（未完全释放）
    Note over T1: state=1，锁仍被持有
    
    T1->>Lock: unlock() - 第3次释放
    Lock->>AQS: tryRelease(1)
    AQS->>AQS: c = state - 1 = 1 - 1 = 0
    Note over AQS: c = 0，完全释放
    AQS->>AQS: setExclusiveOwnerThread(null)
    AQS->>AQS: setState(0)
    AQS-->>Lock: return true（完全释放）
    Lock->>AQS: 唤醒等待队列中的线程
    Note over T1: state=0，锁完全释放，其他线程可以获取
```

#### 5.3.2 重入锁释放的代码示例

```java
public class ReentrantLockReleaseExample {
    private ReentrantLock lock = new ReentrantLock();
    
    public void method1() {
        lock.lock();  // 第1次获取锁，state = 1
        try {
            System.out.println("method1: state = " + getState());
            method2();  // 调用method2，会再次获取锁
        } finally {
            lock.unlock();  // 第1次释放，state = 2 -> 1
            System.out.println("method1 unlock: state = " + getState());
        }
    }
    
    public void method2() {
        lock.lock();  // 第2次获取锁（重入），state = 1 -> 2
        try {
            System.out.println("method2: state = " + getState());
            method3();  // 调用method3，会再次获取锁
        } finally {
            lock.unlock();  // 第2次释放，state = 2 -> 1
            System.out.println("method2 unlock: state = " + getState());
        }
    }
    
    public void method3() {
        lock.lock();  // 第3次获取锁（重入），state = 1 -> 2 -> 3
        try {
            System.out.println("method3: state = " + getState());
            // 临界区代码
        } finally {
            lock.unlock();  // 第3次释放，state = 3 -> 2 -> 1 -> 0
            System.out.println("method3 unlock: state = " + getState());
        }
    }
    
    // 注意：实际代码中无法直接获取state，这里仅用于说明
    private int getState() {
        // 需要通过反射或其他方式获取，这里仅作演示
        return 0;
    }
}
```

**执行流程：**
1. `method1()` 调用 `lock()` → state = 1
2. `method1()` 调用 `method2()` → `method2()` 调用 `lock()` → state = 2（重入）
3. `method2()` 调用 `method3()` → `method3()` 调用 `lock()` → state = 3（再次重入）
4. `method3()` 执行完毕，调用 `unlock()` → state = 3 → 2（部分释放）
5. `method2()` 执行完毕，调用 `unlock()` → state = 2 → 1（部分释放）
6. `method1()` 执行完毕，调用 `unlock()` → state = 1 → 0（完全释放）

#### 5.3.3 什么情况下会多次获取锁？

**场景1：递归调用**

```java
public class RecursiveExample {
    private ReentrantLock lock = new ReentrantLock();
    
    public void recursiveMethod(int n) {
        lock.lock();  // 每次递归都会获取锁
        try {
            if (n > 0) {
                System.out.println("递归深度: " + n);
                recursiveMethod(n - 1);  // 递归调用，会再次获取锁
            }
        } finally {
            lock.unlock();  // 每次递归返回时都会释放锁
        }
    }
}
```

**场景2：方法嵌套调用**

```java
public class NestedMethodExample {
    private ReentrantLock lock = new ReentrantLock();
    
    public void outerMethod() {
        lock.lock();  // 第1次获取
        try {
            innerMethod();  // 调用内部方法
        } finally {
            lock.unlock();  // 第1次释放
        }
    }
    
    private void innerMethod() {
        lock.lock();  // 第2次获取（重入）
        try {
            // 临界区代码
        } finally {
            lock.unlock();  // 第2次释放
        }
    }
}
```

**场景3：回调函数**

```java
public class CallbackExample {
    private ReentrantLock lock = new ReentrantLock();
    
    public void processData(List<String> data) {
        lock.lock();  // 第1次获取
        try {
            data.forEach(item -> {
                lock.lock();  // 第2次获取（重入）
                try {
                    // 处理每个item
                } finally {
                    lock.unlock();  // 第2次释放
                }
            });
        } finally {
            lock.unlock();  // 第1次释放
        }
    }
}
```

**场景4：继承和重写**

```java
public class BaseClass {
    protected ReentrantLock lock = new ReentrantLock();
    
    public void baseMethod() {
        lock.lock();  // 第1次获取
        try {
            // 基类逻辑
            overriddenMethod();  // 调用子类重写的方法
        } finally {
            lock.unlock();  // 第1次释放
        }
    }
    
    protected void overriddenMethod() {
        // 子类可以重写此方法
    }
}

public class DerivedClass extends BaseClass {
    @Override
    protected void overriddenMethod() {
        lock.lock();  // 第2次获取（重入）
        try {
            // 子类逻辑
        } finally {
            lock.unlock();  // 第2次释放
        }
    }
}
```

#### 5.3.4 重入锁释放的关键要点

1. **对称性**：获取锁的次数必须等于释放锁的次数
   ```java
   lock.lock();      // 获取1次
   lock.lock();      // 获取2次（重入）
   lock.unlock();    // 释放1次，state = 2 -> 1
   lock.unlock();    // 释放2次，state = 1 -> 0（完全释放）
   ```

2. **部分释放不会唤醒等待线程**：只有当 `tryRelease()` 返回 `true`（state = 0）时，才会唤醒等待队列中的线程

3. **异常安全**：必须使用 `try-finally` 确保每次 `lock()` 都有对应的 `unlock()`
   ```java
   lock.lock();
   try {
       // 临界区代码
   } finally {
       lock.unlock();  // 确保释放
   }
   ```

4. **释放顺序**：必须按照获取的相反顺序释放（LIFO - Last In First Out）

#### 5.3.5 重入锁释放流程图

```mermaid
flowchart TD
    Start[调用unlock]
    CheckThread{当前线程是<br/>持有锁的线程?}
    GetState[获取当前state值]
    CalcNewState[计算新state<br/>c = state - 1]
    CheckZero{c == 0?}
    SetOwnerNull[setExclusiveOwnerThread<br/>null]
    SetStateZero[setState 0]
    SetStateNew[setState c]
    ReturnTrue[返回true<br/>完全释放]
    ReturnFalse[返回false<br/>部分释放]
    WakeUp[唤醒等待队列中的线程]
    Error[抛出IllegalMonitorStateException]
    
    Start --> CheckThread
    CheckThread -->|否| Error
    CheckThread -->|是| GetState
    GetState --> CalcNewState
    CalcNewState --> CheckZero
    CheckZero -->|是| SetOwnerNull
    CheckZero -->|否| SetStateNew
    SetOwnerNull --> SetStateZero
    SetStateZero --> ReturnTrue
    SetStateNew --> ReturnFalse
    ReturnTrue --> WakeUp
    ReturnFalse --> End1[结束，锁仍被持有]
    WakeUp --> End2[结束，锁完全释放]
    
    style ReturnTrue fill:#c8e6c9
    style ReturnFalse fill:#fff9c4
    style Error fill:#ffcdd2
    style WakeUp fill:#e1f5ff
```

## 6. 核心方法详细分析

### 6.1 lock() 方法实现原理

**非公平锁实现（NonfairSync.lock()）：**

```java
final void lock() {
    // 1. 直接尝试CAS获取锁（非公平：可能插队）
    if (compareAndSetState(0, 1))
        setExclusiveOwnerThread(Thread.currentThread());
    else
        // 2. CAS失败，调用AQS的acquire方法
        acquire(1);
}
```

**公平锁实现（FairSync.lock()）：**

```java
final void lock() {
    // 直接调用acquire，不会先尝试CAS
    acquire(1);
}
```

### 6.2 tryAcquire() 方法实现

**非公平锁的 tryAcquire：**

```java
protected final boolean tryAcquire(int acquires) {
    return nonfairTryAcquire(acquires);
}

final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    
    // 1. 锁未被占用
    if (c == 0) {
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    // 2. 锁已被占用，检查是否为重入
    else if (current == getExclusiveOwnerThread()) {
        int nextc = c + acquires;
        if (nextc < 0) // overflow
            throw new Error("Maximum lock count exceeded");
        setState(nextc); // 重入：直接更新state，无需CAS
        return true;
    }
    return false;
}
```

**公平锁的 tryAcquire：**

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    
    if (c == 0) {
        // 公平锁：检查是否有其他线程在等待
        if (!hasQueuedPredecessors() && 
            compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    // 重入逻辑与非公平锁相同
    else if (current == getExclusiveOwnerThread()) {
        int nextc = c + acquires;
        if (nextc < 0)
            throw new Error("Maximum lock count exceeded");
        setState(nextc);
        return true;
    }
    return false;
}
```

### 6.3 hasQueuedPredecessors() 方法

```java
public final boolean hasQueuedPredecessors() {
    Node t = tail;
    Node h = head;
    Node s;
    // 检查队列中是否有其他线程在等待
    return h != t &&
        ((s = h.next) == null || s.thread != Thread.currentThread());
}
```

### 6.4 tryRelease() 方法实现

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;
    
    // 1. 检查是否为持有锁的线程
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();
    
    boolean free = false;
    // 2. 完全释放锁（state变为0）
    if (c == 0) {
        free = true;
        setExclusiveOwnerThread(null);
    }
    // 3. 更新state
    setState(c);
    return free;
}
```

## 7. Condition 条件变量

`ReentrantLock` 通过 `newCondition()` 方法支持多个条件变量，提供了比 `Object.wait/notify` 更灵活的条件等待机制。

### 7.1 Condition 结构图

```mermaid
graph TB
    ReentrantLock[ReentrantLock]
    Condition1[Condition 1<br/>等待队列1]
    Condition2[Condition 2<br/>等待队列2]
    AQS[AQS<br/>CLH队列]
    
    ReentrantLock -->|newCondition| Condition1
    ReentrantLock -->|newCondition| Condition2
    Condition1 --> AQS
    Condition2 --> AQS
    
    subgraph CLH队列
        T1[Thread1]
        T2[Thread2]
    end
    
    subgraph 条件队列1
        C1[Thread3]
        C2[Thread4]
    end
    
    subgraph 条件队列2
        C3[Thread5]
    end
    
    AQS --> CLH队列
    Condition1 --> 条件队列1
    Condition2 --> 条件队列2
    
    style ReentrantLock fill:#e1f5ff
    style Condition1 fill:#fff9c4
    style Condition2 fill:#fff9c4
```

### 7.2 await() 方法详解

#### 7.2.1 await() 的核心原理

`await()` 方法的主要作用是：**释放当前持有的锁，将当前线程加入到条件队列中等待，直到被 signal() 唤醒。**

**关键理解：**
- `await()` 必须在持有锁的情况下调用（否则抛出 `IllegalMonitorStateException`）
- 调用 `await()` 会**完全释放锁**（即使有重入，也会全部释放）
- 线程会从**同步队列（CLH队列）**转移到**条件队列**中
- 被唤醒后，线程会重新尝试获取锁

#### 7.2.2 await() 的完整流程

```java
public final void await() throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException();
    
    // 1. 创建条件节点，加入条件队列
    Node node = addConditionWaiter();
    
    // 2. 完全释放锁（包括所有重入）
    int savedState = fullyRelease(node);
    int interruptMode = 0;
    
    // 3. 循环检查节点是否在同步队列中
    while (!isOnSyncQueue(node)) {
        // 4. 如果不在同步队列，说明还在条件队列中，阻塞等待
        LockSupport.park(this);
        
        // 5. 检查是否被中断
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;
    }
    
    // 6. 节点已被转移到同步队列，尝试获取锁
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;
    
    // 7. 清理已取消的节点
    if (node.nextWaiter != null)
        unlinkCancelledWaiters();
    
    // 8. 处理中断
    if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode);
}
```

#### 7.2.3 await() 详细步骤解析

**步骤1：addConditionWaiter() - 创建条件节点**

```java
private Node addConditionWaiter() {
    Node t = lastWaiter;
    // 清理已取消的节点
    if (t != null && t.waitStatus != Node.CONDITION) {
        unlinkCancelledWaiters();
        t = lastWaiter;
    }
    
    // 创建新节点，waitStatus = CONDITION
    Node node = new Node(Thread.currentThread(), Node.CONDITION);
    
    if (t == null)
        firstWaiter = node;
    else
        t.nextWaiter = node;
    lastWaiter = node;
    
    return node;
}
```

**步骤2：fullyRelease() - 完全释放锁**

```java
final int fullyRelease(Node node) {
    boolean failed = true;
    try {
        int savedState = getState();  // 保存当前state（可能 > 1，因为有重入）
        
        // 完全释放锁（即使state > 1，也释放到0）
        if (release(savedState)) {
            failed = false;
            return savedState;  // 返回保存的state，用于后续重新获取锁
        } else {
            throw new IllegalMonitorStateException();
        }
    } finally {
        if (failed)
            node.waitStatus = Node.CANCELLED;
    }
}
```

**步骤3：isOnSyncQueue() - 检查节点是否在同步队列**

```java
final boolean isOnSyncQueue(Node node) {
    // 如果waitStatus是CONDITION，说明还在条件队列中
    if (node.waitStatus == Node.CONDITION)
        return false;
    
    // 如果有前驱节点，说明已经在同步队列中
    if (node.prev != null)
        return true;
    
    // 进一步检查（可能正在转移过程中）
    return findNodeFromTail(node);
}
```

#### 7.2.4 await() 完整流程图

```mermaid
sequenceDiagram
    participant T1 as Thread1<br/>已持有锁
    participant Condition as Condition
    participant AQS as AQS
    participant CondQueue as 条件队列
    participant CLHQueue as CLH队列
    
    Note over T1: state=2, owner=T1<br/>（重入了1次）
    
    T1->>Condition: await()
    Condition->>Condition: addConditionWaiter()
    Note over Condition: 创建节点node<br/>waitStatus=CONDITION
    Condition->>CondQueue: 加入条件队列尾部
    Note over CondQueue: [T1节点, waitStatus=CONDITION]
    
    Condition->>AQS: fullyRelease(node)
    Note over AQS: savedState = 2<br/>（保存重入次数）
    AQS->>AQS: release(2)
    AQS->>AQS: tryRelease(2)
    Note over AQS: state: 2 -> 0<br/>完全释放锁
    AQS->>AQS: setExclusiveOwnerThread(null)
    AQS->>CLHQueue: unparkSuccessor(head)
    Note over CLHQueue: 唤醒等待队列中的线程
    
    Condition->>AQS: isOnSyncQueue(node)?
    Note over AQS: node.waitStatus = CONDITION
    AQS-->>Condition: false（在条件队列中）
    
    Condition->>T1: LockSupport.park(this)
    Note over T1: T1被阻塞<br/>等待signal唤醒
    
    Note over CLHQueue: 其他线程可以获取锁了
```

#### 7.2.5 await() 与 lock() 的区别

| 特性 | lock() | await() |
|------|--------|---------|
| **前提条件** | 无（尝试获取锁） | 必须已持有锁 |
| **队列类型** | 同步队列（CLH队列） | 条件队列 → 同步队列 |
| **锁释放** | 获取锁（state: 0→1） | 完全释放锁（state: n→0） |
| **等待原因** | 等待获取锁 | 等待条件满足 |
| **节点状态** | waitStatus = SIGNAL | waitStatus = CONDITION |

### 7.3 signal() 方法详解

#### 7.3.1 signal() 的核心原理

`signal()` 方法的主要作用是：**将条件队列中的第一个等待节点转移到同步队列中，让该线程有机会重新获取锁。**

**关键理解：**
- `signal()` 必须在持有锁的情况下调用
- `signal()` **不会立即唤醒线程**，只是将节点从条件队列转移到同步队列
- 被转移的线程需要等待当前线程释放锁后，才能尝试获取锁
- 如果当前线程一直不释放锁，被signal的线程会一直等待

#### 7.3.2 signal() 的完整流程

```java
public final void signal() {
    // 1. 检查当前线程是否持有锁
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    
    // 2. 获取条件队列的第一个节点
    Node first = firstWaiter;
    if (first != null)
        // 3. 转移节点到同步队列
        doSignal(first);
}

private void doSignal(Node first) {
    do {
        // 从条件队列中移除第一个节点
        if ((firstWaiter = first.nextWaiter) == null)
            lastWaiter = null;
        first.nextWaiter = null;
    } while (!transferForSignal(first) &&  // 转移节点到同步队列
             (first = firstWaiter) != null);
}

final boolean transferForSignal(Node node) {
    // 1. 尝试将waitStatus从CONDITION改为0
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
        return false;  // 节点已被取消
    
    // 2. 将节点加入同步队列尾部
    Node p = enq(node);
    int ws = p.waitStatus;
    
    // 3. 如果前驱节点已取消，或设置SIGNAL失败，立即唤醒
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
        LockSupport.unpark(node.thread);
    
    return true;
}
```

#### 7.3.3 signal() 详细步骤解析

**步骤1：获取条件队列的第一个节点**

```java
Node first = firstWaiter;
// first 指向条件队列的第一个等待节点
```

**步骤2：transferForSignal() - 转移节点**

```java
final boolean transferForSignal(Node node) {
    // 1. CAS将waitStatus从CONDITION改为0
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
        return false;  // 如果失败，说明节点已被取消
    
    // 2. 将节点加入同步队列尾部
    Node p = enq(node);  // p是node的前驱节点
    
    // 3. 设置前驱节点的waitStatus = SIGNAL
    int ws = p.waitStatus;
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
        // 如果前驱节点已取消，或设置SIGNAL失败，立即唤醒线程
        LockSupport.unpark(node.thread);
    
    return true;
}
```

**步骤3：enq() - 加入同步队列**

```java
private Node enq(final Node node) {
    for (;;) {
        Node t = tail;
        if (t == null) {
            // 初始化队列
            if (compareAndSetHead(new Node()))
                tail = head;
        } else {
            node.prev = t;
            if (compareAndSetTail(t, node)) {
                t.next = node;
                return t;  // 返回前驱节点
            }
        }
    }
}
```

#### 7.3.4 signal() 完整流程图

```mermaid
sequenceDiagram
    participant T2 as Thread2<br/>持有锁
    participant Condition as Condition
    participant CondQueue as 条件队列
    participant AQS as AQS
    participant CLHQueue as CLH队列
    participant T1 as Thread1<br/>等待中
    
    Note over T2: state=1, owner=T2<br/>持有锁
    Note over CondQueue: [T1节点, waitStatus=CONDITION]
    Note over T1: T1在await()中阻塞
    
    T2->>Condition: signal()
    Condition->>Condition: isHeldExclusively()
    Note over Condition: 检查T2是否持有锁
    
    Condition->>CondQueue: 获取firstWaiter = T1节点
    Condition->>AQS: transferForSignal(T1节点)
    
    AQS->>AQS: compareAndSetWaitStatus<br/>(T1节点, CONDITION, 0)
    Note over AQS: T1节点waitStatus: CONDITION -> 0
    
    AQS->>AQS: enq(T1节点)
    AQS->>CLHQueue: 将T1节点加入同步队列尾部
    Note over CLHQueue: [head] -> [其他节点] -> [T1节点]
    
    AQS->>AQS: 设置前驱节点waitStatus = SIGNAL
    AQS->>T1: LockSupport.unpark(T1)
    Note over T1: T1被唤醒<br/>但T2仍持有锁
    
    Note over T1: T1继续执行await()中的循环
    T1->>AQS: isOnSyncQueue(T1节点)?
    Note over AQS: T1节点.prev != null
    AQS-->>T1: true（已在同步队列中）
    
    T1->>AQS: acquireQueued(T1节点, savedState=2)
    Note over AQS: T1尝试获取锁<br/>但T2仍持有，会失败
    
    Note over T2: T2执行完毕
    T2->>AQS: unlock() - 释放锁
    AQS->>AQS: tryRelease(1) - state: 1->0
    AQS->>AQS: unparkSuccessor(head)
    AQS->>T1: 唤醒T1（如果T1在队列第一个）
    
    T1->>AQS: acquireQueued继续自旋
    T1->>AQS: tryAcquire(2) - 成功
    Note over AQS: state=2, owner=T1<br/>恢复重入状态
    T1->>T1: await()返回，继续执行
```

#### 7.3.5 signal() 与 notify() 的区别

| 特性 | Object.notify() | Condition.signal() |
|------|-----------------|-------------------|
| **立即唤醒** | 是，立即唤醒等待线程 | 否，只是转移到同步队列 |
| **必须释放锁** | 否，notify后仍持有锁 | 否，signal后仍持有锁 |
| **唤醒时机** | notify后，等待线程可能立即获取锁 | signal后，需要等待当前线程释放锁 |
| **精确唤醒** | 否，随机唤醒一个 | 是，FIFO顺序唤醒 |

### 7.4 await() 和 signal() 完整交互流程

#### 7.4.1 完整场景示例

**场景：生产者-消费者模式**

```java
public class ProducerConsumer {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();   // 条件队列1
    private final Condition notEmpty = lock.newCondition();  // 条件队列2
    private Queue<String> queue = new LinkedList<>();
    private final int CAPACITY = 10;
    
    // 生产者
    public void produce(String item) throws InterruptedException {
        lock.lock();
        try {
            // 队列满，等待
            while (queue.size() == CAPACITY) {
                notFull.await();  // 释放锁，加入notFull条件队列
            }
            queue.offer(item);
            notEmpty.signal();  // 通知消费者
        } finally {
            lock.unlock();
        }
    }
    
    // 消费者
    public String consume() throws InterruptedException {
        lock.lock();
        try {
            // 队列空，等待
            while (queue.isEmpty()) {
                notEmpty.await();  // 释放锁，加入notEmpty条件队列
            }
            String item = queue.poll();
            notFull.signal();  // 通知生产者
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

#### 7.4.2 完整交互时序图

```mermaid
sequenceDiagram
    participant Producer as 生产者线程
    participant Consumer as 消费者线程
    participant Lock as ReentrantLock
    participant NotFull as notFull条件队列
    participant NotEmpty as notEmpty条件队列
    participant CLHQueue as CLH同步队列
    
    Note over Producer: 队列已满（size=10）
    Producer->>Lock: lock() - 获取锁
    Producer->>Producer: 检查队列size == CAPACITY
    Producer->>NotFull: await()
    
    Note over NotFull: Producer节点加入条件队列<br/>waitStatus=CONDITION
    NotFull->>Lock: fullyRelease()
    Note over Lock: state: 1 -> 0<br/>完全释放锁
    Lock->>CLHQueue: 唤醒等待线程
    Producer->>Producer: LockSupport.park() - 阻塞
    
    Note over Consumer: 队列未满，消费一个元素
    Consumer->>Lock: lock() - 获取锁成功
    Consumer->>Consumer: queue.poll() - 消费
    Consumer->>NotFull: signal()
    
    NotFull->>AQS: transferForSignal(Producer节点)
    AQS->>NotFull: 从条件队列移除Producer节点
    AQS->>CLHQueue: 将Producer节点加入同步队列尾部
    AQS->>Producer: LockSupport.unpark() - 唤醒
    
    Note over Producer: 被唤醒，但Consumer仍持有锁
    Producer->>CLHQueue: acquireQueued(Producer节点, savedState)
    Producer->>Lock: tryAcquire(1) - 失败（Consumer持有）
    
    Consumer->>Lock: unlock() - 释放锁
    Lock->>CLHQueue: unparkSuccessor()
    Lock->>Producer: 再次唤醒Producer
    
    Producer->>Lock: tryAcquire(1) - 成功
    Note over Lock: state=1, owner=Producer
    Producer->>Producer: await()返回，继续执行
    Producer->>Producer: queue.offer(item) - 生产
    Producer->>NotEmpty: signal()
    Producer->>Lock: unlock()
```

#### 7.4.3 await() 和 signal() 流程

这个流程图展示了 await() 和 signal() 的基本交互流程，重点突出节点在两个队列之间的转移过程。

```mermaid
sequenceDiagram
    participant Thread as 持有锁的线程
    participant Lock as ReentrantLock
    participant Condition as Condition
    participant AQS as AQS
    participant CondQueue as 条件队列
    participant CLHQueue as CLH队列
    
    Note over Thread: 已持有锁，调用await()
    Thread->>Lock: await()
    Lock->>AQS: fullyRelease(node)
    Note over AQS: 完全释放锁（state=0）
    AQS->>CLHQueue: 唤醒等待线程
    Lock->>Condition: addConditionWaiter()
    Condition->>CondQueue: 将节点加入条件队列尾部
    Lock->>AQS: isOnSyncQueue(node)?
    AQS-->>Lock: false
    Lock->>AQS: park() 阻塞当前线程
    
    Note over Thread: 其他线程调用signal()
    Thread->>Condition: signal()
    Condition->>CondQueue: 获取第一个等待节点
    Condition->>AQS: transferForSignal(node)
    AQS->>CondQueue: 从条件队列移除
    AQS->>CLHQueue: 加入CLH队列尾部
    AQS->>AQS: unpark(node.thread)
    Note over Thread: 原线程被唤醒
    Thread->>AQS: acquireQueued(node, savedState)
    AQS->>Lock: tryAcquire(savedState)
    Lock-->>AQS: 重新获取锁
    AQS-->>Thread: 继续执行
```

**流程关键步骤说明：**

1. **await() 阶段**：
   - 线程调用 `await()` 时已持有锁
   - `fullyRelease()` 完全释放锁（即使有重入也全部释放）
   - 创建节点并加入条件队列（waitStatus = CONDITION）
   - 线程被 `park()` 阻塞

2. **signal() 阶段**：
   - 其他线程调用 `signal()`（必须持有锁）
   - `transferForSignal()` 将节点从条件队列转移到同步队列
   - 节点的 waitStatus 从 CONDITION 变为 0
   - 节点被加入同步队列尾部

3. **唤醒和重新获取锁**：
   - 节点被转移到同步队列后，线程被 `unpark()` 唤醒
   - 线程在 `acquireQueued()` 中自旋，尝试获取锁
   - 使用 `savedState`（保存的重入次数）重新获取锁
   - 获取成功后，`await()` 返回，线程继续执行

**节点转移过程：**

```mermaid
graph LR
    subgraph "await()前"
        A1[Thread持有锁<br/>在同步队列或执行中]
    end
    
    subgraph "await()后"
        A2[Thread节点<br/>waitStatus=CONDITION<br/>在条件队列中]
    end
    
    subgraph "signal()后"
        A3[Thread节点<br/>waitStatus=0<br/>在同步队列尾部]
    end
    
    subgraph "重新获取锁后"
        A4[Thread持有锁<br/>继续执行]
    end
    
    A1 -->|await| A2
    A2 -->|signal| A3
    A3 -->|acquireQueued| A4
    
    style A1 fill:#c8e6c9
    style A2 fill:#fff9c4
    style A3 fill:#e1f5ff
    style A4 fill:#c8e6c9
```

#### 7.4.4 为什么 await() 和 signal() 都必须先持有锁？

这是一个非常重要的设计问题。理解这个原因有助于正确使用 Condition。

##### 7.4.4.1 await() 必须先持有锁的原因

**原因1：原子性保证 - 检查条件状态和进入等待的原子性**

```java
// 错误的用法（没有锁）
public void consume() {
    while (queue.isEmpty()) {  // 检查条件
        // 问题：在这里可能其他线程修改了queue，导致状态不一致
        condition.await();      // 进入等待
    }
}

// 正确的用法（有锁保护）
public void consume() {
    lock.lock();
    try {
        while (queue.isEmpty()) {  // 在持有锁的情况下检查条件
            condition.await();      // 原子地释放锁并进入等待
        }
    } finally {
        lock.unlock();
    }
}
```

**竞态条件示例：**

```mermaid
sequenceDiagram
    participant T1 as Thread1（消费者）
    participant T2 as Thread2（生产者）
    participant Queue as 队列
    
    Note over T1: 没有锁保护
    T1->>Queue: 检查queue.isEmpty()
    Queue-->>T1: true（队列为空）
    
    Note over T2: 此时T2可以修改队列
    T2->>Queue: queue.offer(item) - 添加元素
    Note over Queue: 队列：空 → 有1个元素
    
    Note over T1: T1不知道队列已被修改
    T1->>T1: await() - 进入等待
    Note over T1: 问题：队列已经有元素了，<br/>但T1还在等待！
    
    Note over T2: T2调用signal()
    T2->>T1: 唤醒T1
    Note over T1: 但可能为时已晚，<br/>或需要再次signal
```

**原因2：需要原子地释放锁并加入条件队列**

```java
public final void await() throws InterruptedException {
    // 1. 必须已经持有锁，才能调用 fullyRelease()
    int savedState = fullyRelease(node);  // 完全释放锁
    
    // 2. 将节点加入条件队列（必须在持有锁时检查状态）
    Node node = addConditionWaiter();
    
    // 3. 如果不在持有锁的情况下，无法安全地操作这些步骤
}
```

如果没有锁保护，可能出现的问题：
- 多个线程同时调用 `await()`，导致条件队列状态不一致
- 在检查和等待之间，其他线程修改了共享状态
- 无法正确保存和恢复锁的重入状态

**原因3：防止死锁和竞态条件**

```mermaid
graph TB
    subgraph "没有锁保护 await"
        A1[检查条件状态]
        A2[其他线程修改状态]
        A3[await进入等待]
        A4[信号丢失或重复]
        
        A1 --> A2
        A2 --> A3
        A3 --> A4
        
        style A2 fill:#ffcdd2
        style A4 fill:#ffcdd2
    end
    
    subgraph "有锁保护 await"
        B1[获取锁]
        B2[检查条件状态]
        B3[原子地释放锁并等待]
        B4[被唤醒后重新获取锁]
        
        B1 --> B2
        B2 --> B3
        B3 --> B4
        
        style B1 fill:#c8e6c9
        style B3 fill:#c8e6c9
        style B4 fill:#c8e6c9
    end
```

##### 7.4.4.2 signal() 必须先持有锁的原因

**原因1：原子性保证 - 修改条件状态和唤醒线程的原子性**

```java
// 错误的用法（没有锁）
public void produce() {
    queue.offer(item);        // 修改共享状态
    condition.signal();       // 唤醒等待线程
    // 问题：signal后，其他线程可能立即修改queue，导致被唤醒的线程看到错误的状态
}

// 正确的用法（有锁保护）
public void produce() {
    lock.lock();
    try {
        queue.offer(item);        // 在持有锁的情况下修改状态
        condition.signal();       // 原子地唤醒等待线程
    } finally {
        lock.unlock();           // 释放锁，让被唤醒的线程可以获取锁
    }
}
```

**丢失信号（Lost Wake-up）问题：**

```mermaid
sequenceDiagram
    participant T1 as Thread1（消费者）
    participant T2 as Thread2（生产者）
    participant Queue as 队列
    
    Note over T1: T1检查队列为空，准备await
    T1->>Queue: queue.isEmpty()
    Queue-->>T1: true
    
    Note over T2: T2在没有锁的情况下操作
    T2->>Queue: queue.offer(item)
    T2->>T1: signal() - 唤醒T1
    
    Note over T1: 但T1还没有await
    T1->>T1: await() - 进入等待
    Note over T1: 问题：signal在await之前，<br/>信号丢失！
    
    Note over Queue: 队列有元素，但没有线程处理
```

**原因2：确保条件状态的一致性**

```java
public final void signal() {
    // 1. 检查是否持有锁
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    
    // 2. 必须在持有锁的情况下，确保条件状态的修改是可见的
    Node first = firstWaiter;
    if (first != null)
        doSignal(first);
}
```

如果不持有锁，可能出现的问题：
- 在 `signal()` 调用时，其他线程可能正在修改条件状态
- 被唤醒的线程看到的可能是不一致的状态
- 多个线程同时调用 `signal()`，导致条件队列状态混乱

**原因3：保证正确的执行顺序**

```java
// 正确的执行顺序
lock.lock();
try {
    // 1. 修改条件状态（在持有锁的情况下）
    queue.offer(item);
    
    // 2. 唤醒等待线程（在持有锁的情况下）
    condition.signal();
    
    // 3. 释放锁（让被唤醒的线程可以获取锁）
} finally {
    lock.unlock();
}
```

##### 7.4.4.3 完整的执行流程对比

**正确的流程（有锁保护）：**

```mermaid
sequenceDiagram
    participant T1 as Thread1（消费者）
    participant T2 as Thread2（生产者）
    participant Lock as ReentrantLock
    participant Queue as 队列
    
    Note over T1: 持有锁，检查条件
    T1->>Lock: lock()
    T1->>Queue: queue.isEmpty()
    Queue-->>T1: true
    T1->>T1: await() - 释放锁并等待
    Note over Lock: state=0，锁已释放
    
    Note over T2: 获取锁，修改状态
    T2->>Lock: lock() - 成功获取
    T2->>Queue: queue.offer(item)
    T2->>T1: signal() - 唤醒T1
    Note over Lock: T2仍持有锁
    
    T2->>Lock: unlock() - 释放锁
    Note over T1: T1被唤醒，获取锁
    T1->>Lock: lock() - 重新获取锁
    T1->>Queue: queue.poll() - 消费
    T1->>Lock: unlock()
```

**错误的流程（没有锁保护）：**

```mermaid
sequenceDiagram
    participant T1 as Thread1
    participant T2 as Thread2
    participant Queue as 队列
    
    Note over T1: 没有锁保护
    T1->>Queue: queue.isEmpty()
    Queue-->>T1: true
    
    Note over T2: T2同时修改队列
    T2->>Queue: queue.offer(item)
    T2->>T1: signal()
    
    Note over T1: T1不知道状态已改变
    T1->>T1: await() - 进入等待
    Note over T1: 问题：队列已有元素，<br/>但T1在等待，信号丢失
```

##### 7.4.4.4 await() 和 signal() 必须使用同一把锁

**关键点：await() 和 signal() 不仅必须先持有锁，还必须使用同一把锁！**

**原因1：Condition 对象与 ReentrantLock 绑定**

```java
// Condition 对象是通过 ReentrantLock 实例创建的
ReentrantLock lock = new ReentrantLock();
Condition condition = lock.newCondition();  // Condition与lock绑定

// await() 和 signal() 内部会检查是否持有同一个锁
public final void await() throws InterruptedException {
    // 内部会检查：当前线程是否持有创建这个Condition的ReentrantLock
    if (!isHeldExclusively())  // 检查的是创建Condition的lock
        throw new IllegalMonitorStateException();
    // ...
}

public final void signal() {
    // 内部会检查：当前线程是否持有创建这个Condition的ReentrantLock
    if (!isHeldExclusively())  // 检查的是创建Condition的lock
        throw new IllegalMonitorStateException();
    // ...
}
```

**错误示例：使用不同的锁**

```java
// ❌ 错误：await 和 signal 使用不同的锁
public class WrongExample {
    private ReentrantLock lock1 = new ReentrantLock();
    private ReentrantLock lock2 = new ReentrantLock();
    private Condition condition1 = lock1.newCondition();  // 从lock1创建
    private Condition condition2 = lock2.newCondition();  // 从lock2创建
    
    public void method1() throws InterruptedException {
        lock1.lock();  // 持有lock1
        try {
            condition1.await();  // 等待condition1（关联lock1）✓
        } finally {
            lock1.unlock();
        }
    }
    
    public void method2() {
        lock2.lock();  // 持有lock2
        try {
            condition1.signal();  // ❌ 错误！condition1关联的是lock1，不是lock2
            // 会抛出 IllegalMonitorStateException
        } finally {
            lock2.unlock();
        }
    }
}
```

**正确示例：使用同一把锁**

```java
// ✅ 正确：await 和 signal 使用同一把锁
public class CorrectExample {
    private ReentrantLock lock = new ReentrantLock();
    private Condition condition = lock.newCondition();  // 从同一个lock创建
    
    public void awaitMethod() throws InterruptedException {
        lock.lock();  // 持有lock
        try {
            condition.await();  // 等待（关联lock）✓
        } finally {
            lock.unlock();
        }
    }
    
    public void signalMethod() {
        lock.lock();  // 持有同一个lock
        try {
            condition.signal();  // 唤醒（关联同一个lock）✓
        } finally {
            lock.unlock();
        }
    }
}
```

**原因2：内部实现机制要求**

Condition 对象内部持有对创建它的 ReentrantLock 的引用：

```java
// ConditionObject 内部（简化版）
public class ConditionObject implements Condition {
    private final AbstractQueuedSynchronizer sync;  // 关联的AQS（来自ReentrantLock）
    
    ConditionObject(AbstractQueuedSynchronizer sync) {
        this.sync = sync;  // 保存对ReentrantLock内部Sync的引用
    }
    
    public final void await() throws InterruptedException {
        // 检查是否持有创建这个Condition的锁
        if (!sync.isHeldExclusively())  // 检查sync对应的lock
            throw new IllegalMonitorStateException();
        // ...
    }
    
    public final void signal() {
        // 检查是否持有创建这个Condition的锁
        if (!sync.isHeldExclusively())  // 检查sync对应的lock
            throw new IllegalMonitorStateException();
        // ...
    }
}
```

**原因3：队列转移机制要求**

`await()` 和 `signal()` 涉及节点在不同队列之间的转移，这些操作必须在同一个 AQS 实例下进行：

```mermaid
graph TB
    subgraph "同一把锁"
        Lock1[ReentrantLock1]
        Cond1[Condition1 from Lock1]
        AQS1[AQS1]
        Queue1[条件队列1和同步队列1]
        
        Lock1 --> Cond1
        Lock1 --> AQS1
        Cond1 --> AQS1
        AQS1 --> Queue1
    end
    
    subgraph "不同的锁"
        Lock2[ReentrantLock1]
        Lock3[ReentrantLock2]
        Cond2[Condition1 from Lock1]
        Cond3[Condition2 from Lock2]
        AQS2[AQS1]
        AQS3[AQS2]
        
        Lock2 --> Cond2
        Lock3 --> Cond3
        Lock2 --> AQS2
        Lock3 --> AQS3
        Cond2 --> AQS2
        Cond3 --> AQS3
        
        Note3["❌ 无法跨AQS转移节点"]
    end
    
    style Lock1 fill:#c8e6c9
    style Cond1 fill:#c8e6c9
    style Note3 fill:#ffcdd2
```

**原因4：状态同步要求**

`await()` 会完全释放锁，`signal()` 后的线程需要重新获取锁。这个锁必须是创建 Condition 的同一个 ReentrantLock：

```java
public final void await() throws InterruptedException {
    // 1. 创建条件节点
    Node node = addConditionWaiter();
    
    // 2. 完全释放创建Condition的锁（必须是同一个lock）
    int savedState = fullyRelease(node);  // 释放创建Condition的lock
    
    // 3. 等待被signal
    while (!isOnSyncQueue(node)) {
        LockSupport.park(this);
    }
    
    // 4. 重新获取创建Condition的锁（必须是同一个lock）
    if (acquireQueued(node, savedState)) {  // 重新获取创建Condition的lock
        // ...
    }
}
```

**完整示例：验证必须使用同一把锁**

```java
public class ConditionLockTest {
    // 两把不同的锁
    private final ReentrantLock lock1 = new ReentrantLock();
    private final ReentrantLock lock2 = new ReentrantLock();
    
    // Condition从lock1创建
    private final Condition condition = lock1.newCondition();
    
    public void testAwaitWithLock1() throws InterruptedException {
        lock1.lock();  // ✅ 使用创建Condition的锁
        try {
            System.out.println("Thread1 持有 lock1，准备 await");
            condition.await();  // ✅ 成功
            System.out.println("Thread1 被唤醒");
        } finally {
            lock1.unlock();
        }
    }
    
    public void testAwaitWithLock2() throws InterruptedException {
        lock2.lock();  // ❌ 使用不同的锁
        try {
            System.out.println("Thread2 持有 lock2，准备 await");
            condition.await();  // ❌ 抛出 IllegalMonitorStateException
        } finally {
            lock2.unlock();
        }
    }
    
    public void testSignalWithLock1() {
        lock1.lock();  // ✅ 使用创建Condition的锁
        try {
            System.out.println("Thread3 持有 lock1，准备 signal");
            condition.signal();  // ✅ 成功
        } finally {
            lock1.unlock();
        }
    }
    
    public void testSignalWithLock2() {
        lock2.lock();  // ❌ 使用不同的锁
        try {
            System.out.println("Thread4 持有 lock2，准备 signal");
            condition.signal();  // ❌ 抛出 IllegalMonitorStateException
        } finally {
            lock2.unlock();
        }
    }
}
```

**执行结果：**
```
Thread1 持有 lock1，准备 await          // ✅ 成功
Thread3 持有 lock1，准备 signal         // ✅ 成功，Thread1被唤醒
Thread2 持有 lock2，准备 await          // ❌ IllegalMonitorStateException
Thread4 持有 lock2，准备 signal         // ❌ IllegalMonitorStateException
```

**总结：**

| 操作 | 要求 | 原因 |
|------|------|------|
| **await()** | 必须持有创建 Condition 的锁 | Condition 内部检查 `isHeldExclusively()` |
| **signal()** | 必须持有创建 Condition 的锁 | Condition 内部检查 `isHeldExclusively()` |
| **await 和 signal** | 必须使用同一把锁（创建 Condition 的锁） | 节点转移、状态同步、队列操作都需要同一个 AQS |

**关键原则：**
1. Condition 对象通过 `lock.newCondition()` 创建，与 lock 绑定
2. `await()` 和 `signal()` 都必须持有创建 Condition 的同一个锁
3. 不同锁创建的 Condition 不能混用
4. 这是 Condition 机制正确工作的基础要求

##### 7.4.4.5 代码验证

```java
public class ConditionExample {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private Queue<String> queue = new LinkedList<>();
    
    // ✅ 正确：await前持有锁
    public void correctAwait() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                condition.await();  // 原子地释放锁并等待
            }
        } finally {
            lock.unlock();
        }
    }
    
    // ❌ 错误：await前没有持有锁
    public void incorrectAwait() throws InterruptedException {
        // 没有获取锁
        while (queue.isEmpty()) {
            condition.await();  // 抛出 IllegalMonitorStateException
        }
    }
    
    // ✅ 正确：signal前持有锁
    public void correctSignal() {
        lock.lock();
        try {
            queue.offer("item");
            condition.signal();  // 原子地修改状态并唤醒
        } finally {
            lock.unlock();
        }
    }
    
    // ❌ 错误：signal前没有持有锁
    public void incorrectSignal() {
        queue.offer("item");
        condition.signal();  // 抛出 IllegalMonitorStateException
    }
}
```

##### 7.4.4.6 总结

| 方法 | 为什么必须先持有锁 | 如果不持有锁会怎样 |
|------|------------------|------------------|
| **await()** | 1. 原子地检查条件状态和进入等待<br>2. 原子地释放锁并加入条件队列<br>3. 防止竞态条件和丢失信号 | 抛出 `IllegalMonitorStateException`<br>或出现竞态条件 |
| **signal()** | 1. 原子地修改条件状态和唤醒线程<br>2. 确保状态一致性<br>3. 防止丢失信号 | 抛出 `IllegalMonitorStateException`<br>或导致状态不一致 |

**核心原则：**
- **await()** 和 **signal()** 必须在持有锁的情况下调用
- 这保证了条件检查和等待/唤醒操作的原子性
- 这确保了共享状态的一致性
- 这防止了竞态条件和信号丢失

### 7.5 关键要点总结

#### 7.5.1 await() 的关键点

1. **必须先持有锁**：调用 `await()` 前必须获取锁（原因见 7.4.4）
2. **完全释放锁**：即使有重入，也会全部释放（state → 0）
3. **保存重入状态**：`savedState` 保存了重入次数，唤醒后恢复
4. **转移到条件队列**：节点从同步队列转移到条件队列
5. **阻塞等待**：线程被 `LockSupport.park()` 阻塞

#### 7.5.2 signal() 的关键点

1. **必须先持有锁**：调用 `signal()` 前必须获取锁
2. **转移节点**：将节点从条件队列转移到同步队列
3. **不立即唤醒**：只是转移，不立即唤醒（除非前驱节点已取消）
4. **FIFO顺序**：按条件队列的顺序唤醒（先await的先signal）
5. **需要释放锁**：signal后需要释放锁，被signal的线程才能获取锁

#### 7.5.3 两个队列的关系

```mermaid
graph TB
    subgraph "同步队列（CLH队列）"
        CLH1[Thread1<br/>waitStatus: SIGNAL]
        CLH2[Thread2<br/>waitStatus: SIGNAL]
    end
    
    subgraph "条件队列（Condition队列）"
        Cond1[Thread3<br/>waitStatus: CONDITION]
        Cond2[Thread4<br/>waitStatus: CONDITION]
    end
    
    Lock[ReentrantLock<br/>state=1, owner=Thread5]
    
    Lock -->|lock/unlock| CLH1
    Lock -->|await| Cond1
    Cond1 -->|signal| CLH2
    
    Note1["await(): 从同步队列 → 条件队列<br/>signal(): 从条件队列 → 同步队列"]
    
    style Lock fill:#ffebee
    style Cond1 fill:#fff9c4
    style CLH1 fill:#e3f2fd
```

#### 7.5.4 常见问题和注意事项

1. **必须在持有锁时调用**：
   ```java
   // ❌ 错误
   condition.await();  // 抛出IllegalMonitorStateException
   
   // ✅ 正确
   lock.lock();
   try {
       condition.await();
   } finally {
       lock.unlock();
   }
   ```

2. **使用while循环检查条件**：
   ```java
   // ❌ 错误：使用if
   if (queue.isEmpty()) {
       condition.await();
   }
   
   // ✅ 正确：使用while
   while (queue.isEmpty()) {
       condition.await();
   }
   ```

3. **signal()后需要释放锁**：
   ```java
   lock.lock();
   try {
       // 修改条件
       condition.signal();  // 只是转移节点，不立即唤醒
   } finally {
       lock.unlock();  // 释放锁后，被signal的线程才能获取锁
   }
   ```

### 7.6 实际应用场景

#### 7.6.1 生产者-消费者模式

```java
public class BlockingQueue<T> {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Queue<T> queue = new LinkedList<>();
    private final int capacity;
    
    public BlockingQueue(int capacity) {
        this.capacity = capacity;
    }
    
    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity) {
                notFull.await();  // 队列满，等待
            }
            queue.offer(item);
            notEmpty.signal();  // 通知消费者
        } finally {
            lock.unlock();
        }
    }
    
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();  // 队列空，等待
            }
            T item = queue.poll();
            notFull.signal();  // 通知生产者
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

#### 7.6.2 读写锁的实现思路

```java
public class ReadWriteLock {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition readCondition = lock.newCondition();
    private final Condition writeCondition = lock.newCondition();
    private int readers = 0;
    private boolean writer = false;
    
    public void lockRead() throws InterruptedException {
        lock.lock();
        try {
            while (writer) {
                readCondition.await();  // 有写者，等待
            }
            readers++;
        } finally {
            lock.unlock();
        }
    }
    
    public void lockWrite() throws InterruptedException {
        lock.lock();
        try {
            while (writer || readers > 0) {
                writeCondition.await();  // 有写者或读者，等待
            }
            writer = true;
        } finally {
            lock.unlock();
        }
    }
    
    public void unlockRead() {
        lock.lock();
        try {
            readers--;
            if (readers == 0) {
                writeCondition.signal();  // 没有读者了，通知写者
            }
        } finally {
            lock.unlock();
        }
    }
    
    public void unlockWrite() {
        lock.lock();
        try {
            writer = false;
            readCondition.signalAll();   // 通知所有读者
            writeCondition.signal();     // 通知其他写者
        } finally {
            lock.unlock();
        }
    }
}
```

**说明：**
- `lockRead()`: 如果有写者，则等待；否则增加读者数量
- `lockWrite()`: 如果有写者或读者，则等待；否则设置写者标志
- `unlockRead()`: 减少读者数量，如果读者数为0，通知写者
- `unlockWrite()`: 清除写者标志，通知所有等待的读者和写者

**完整示例：使用Condition实现生产者-消费者模式**

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.LinkedList;
import java.util.Queue;

public class ProducerConsumerExample {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();  // 队列非空条件
    private final Condition notFull = lock.newCondition();   // 队列未满条件
    private final Queue<Integer> queue = new LinkedList<>();
    private final int maxSize = 10;
    
    // 生产者
    public void produce(int item) throws InterruptedException {
        lock.lock();
        try {
            // 使用while而不是if，防止虚假唤醒
            while (queue.size() == maxSize) {
                System.out.println("队列已满，生产者等待...");
                notFull.await();  // 队列满，等待
            }
            
            queue.offer(item);
            System.out.println("生产者生产: " + item + ", 队列大小: " + queue.size());
            notEmpty.signal();  // 通知消费者队列非空
        } finally {
            lock.unlock();
        }
    }
    
    // 消费者
    public int consume() throws InterruptedException {
        lock.lock();
        try {
            // 使用while而不是if，防止虚假唤醒
            while (queue.isEmpty()) {
                System.out.println("队列为空，消费者等待...");
                notEmpty.await();  // 队列空，等待
            }
            
            int item = queue.poll();
            System.out.println("消费者消费: " + item + ", 队列大小: " + queue.size());
            notFull.signal();  // 通知生产者队列未满
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    // 测试代码
    public static void main(String[] args) {
        ProducerConsumerExample pc = new ProducerConsumerExample();
        
        // 生产者线程
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    pc.produce(i);
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        // 消费者线程
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    pc.consume();
                    Thread.sleep(150);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        producer.start();
        consumer.start();
    }
}
```

**执行流程分析：**

```mermaid
sequenceDiagram
    participant P as 生产者线程
    participant C as 消费者线程
    participant Lock as ReentrantLock
    participant NotFull as notFull条件
    participant NotEmpty as notEmpty条件
    participant Queue as 队列
    
    Note over P: 生产10个物品后，队列满
    P->>Lock: lock()
    P->>Queue: queue.size() == 10?
    Queue-->>P: true（队列满）
    P->>NotFull: await()
    Note over P: 释放锁，进入条件队列等待
    P->>Lock: unlock()（在await内部）
    
    Note over C: 消费者开始消费
    C->>Lock: lock()
    C->>Queue: queue.poll()
    Queue-->>C: 取出物品
    C->>NotFull: signal()
    Note over NotFull: 将生产者节点转移到同步队列
    C->>Lock: unlock()
    
    Note over P: 生产者被唤醒，重新获取锁
    P->>Lock: lock()（从同步队列获取）
    P->>Queue: queue.offer(item)
    Note over P: 继续生产
```

## 8. 性能优化技术

```mermaid
sequenceDiagram
    participant Thread as 持有锁的线程
    participant Lock as ReentrantLock
    participant Condition as Condition
    participant AQS as AQS
    participant CondQueue as 条件队列
    participant CLHQueue as CLH队列
    
    Note over Thread: 已持有锁，调用await()
    Thread->>Lock: await()
    Lock->>AQS: fullyRelease(node)
    Note over AQS: 完全释放锁（state=0）
    AQS->>CLHQueue: 唤醒等待线程
    Lock->>Condition: addConditionWaiter()
    Condition->>CondQueue: 将节点加入条件队列尾部
    Lock->>AQS: isOnSyncQueue(node)?
    AQS-->>Lock: false
    Lock->>AQS: park() 阻塞当前线程
    
    Note over Thread: 其他线程调用signal()
    Thread->>Condition: signal()
    Condition->>CondQueue: 获取第一个等待节点
    Condition->>AQS: transferForSignal(node)
    AQS->>CondQueue: 从条件队列移除
    AQS->>CLHQueue: 加入CLH队列尾部
    AQS->>AQS: unpark(node.thread)
    Note over Thread: 原线程被唤醒
    Thread->>AQS: acquireQueued(node, savedState)
    AQS->>Lock: tryAcquire(savedState)
    Lock-->>AQS: 重新获取锁
    AQS-->>Thread: 继续执行
```

## 8. 性能优化技术

### 8.1 CAS (Compare-And-Swap) 操作

ReentrantLock 使用 CAS 实现无锁的锁状态更新，避免使用重量级锁：

```mermaid
graph LR
    A[读取state值]
    B[计算新值]
    C[CAS原子操作]
    D{成功?}
    E[更新state]
    F[失败重试]
    
    A --> B
    B --> C
    C --> D
    D -->|是| E
    D -->|否| F
    F --> A
    
    style C fill:#ffebee
    style D fill:#fff9c4
```

### 8.2 自旋优化

在进入阻塞之前，线程会进行有限次数的自旋尝试，减少线程切换开销：

```mermaid
flowchart TD
    Start[尝试获取锁]
    SpinCount{自旋次数<br/>< 阈值?}
    TryCAS[尝试CAS获取]
    CASResult{CAS成功?}
    Park[LockSupport.park<br/>阻塞线程]
    Success[获取成功]
    
    Start --> SpinCount
    SpinCount -->|是| TryCAS
    SpinCount -->|否| Park
    TryCAS --> CASResult
    CASResult -->|成功| Success
    CASResult -->|失败| SpinCount
    
    style SpinCount fill:#fff9c4
    style Park fill:#ffcdd2
    style Success fill:#c8e6c9
```

### 8.3 队列优化

- **虚拟头节点**：简化队列操作，避免边界条件判断
- **懒初始化**：队列在首次需要时才创建
- **节点状态标志**：减少不必要的唤醒操作

## 9. 使用场景和最佳实践

### 9.1 适用场景

1. **需要公平锁的场景**
   ```java
   ReentrantLock fairLock = new ReentrantLock(true);
   ```

2. **需要可中断的锁获取**
   ```java
   try {
       lock.lockInterruptibly();
   } catch (InterruptedException e) {
       // 处理中断
   }
   ```

3. **需要超时机制**
   ```java
   if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
       try {
           // 临界区代码
       } finally {
           lock.unlock();
       }
   }
   ```

4. **需要多个条件变量**
   ```java
   Condition notFull = lock.newCondition();
   Condition notEmpty = lock.newCondition();
   ```

### 9.2 注意事项

1. **必须手动释放锁**：必须在 `finally` 块中调用 `unlock()`
   ```java
   lock.lock();
   try {
       // 临界区代码
   } finally {
       lock.unlock(); // 确保释放
   }
   ```

2. **避免死锁**：多个锁获取时，保持一致的顺序

3. **避免锁泄漏**：确保异常情况下也能释放锁

4. **性能考虑**：Java 6+ 后，synchronized 性能已优化，优先考虑使用 synchronized

### 9.3 性能对比

```mermaid
graph LR
    subgraph "性能因素"
        A[锁竞争激烈程度]
        B[公平性需求]
        C[是否需要高级特性]
    end
    
    subgraph "选择建议"
        D[低竞争<br/>→ synchronized]
        E[高竞争+公平<br/>→ ReentrantLock公平]
        F[高竞争+非公平<br/>→ ReentrantLock非公平]
        G[需要高级特性<br/>→ ReentrantLock]
    end
    
    A --> D
    A --> E
    A --> F
    B --> E
    C --> G
    
    style A fill:#e3f2fd
    style B fill:#fff9c4
    style C fill:#ffebee
```

## 10. 源码关键点分析

### 10.1 重入计数机制

ReentrantLock 使用 `state` 字段记录重入次数：
- `state = 0`：锁未被占用
- `state = 1`：锁被占用，无重入
- `state = n`：锁被同一线程重入了 n-1 次

```mermaid
graph TB
    T1[Thread1调用lock]
    S1[state: 0 → 1]
    T2[Thread1再次lock]
    S2[state: 1 → 2]
    T3[Thread1 unlock]
    S3[state: 2 → 1]
    T4[Thread1 unlock]
    S4[state: 1 → 0]
    
    T1 --> S1
    S1 --> T2
    T2 --> S2
    S2 --> T3
    T3 --> S3
    S3 --> T4
    S4 --> End[锁完全释放]
    
    style S1 fill:#c8e6c9
    style S2 fill:#fff9c4
    style S3 fill:#fff9c4
    style S4 fill:#ffcdd2
```

### 10.2 队列管理机制

```mermaid
sequenceDiagram
    participant T1 as Thread1
    participant T2 as Thread2
    participant T3 as Thread3
    participant Lock as ReentrantLock
    participant Queue as CLH队列
    
    T1->>Lock: lock() - 获取成功
    Note over Lock: state=1, owner=T1
    
    T2->>Lock: lock() - 获取失败
    Lock->>Queue: 加入队列尾部
    Note over Queue: [T2]
    
    T3->>Lock: lock() - 获取失败
    Lock->>Queue: 加入队列尾部
    Note over Queue: [T2, T3]
    
    T1->>Lock: unlock() - 释放锁
    Lock->>Queue: 唤醒T2
    Note over Queue: [T3]
    Note over T2: 被唤醒，获取锁
    
    T2->>Lock: unlock()
    Lock->>Queue: 唤醒T3
    Note over T3: 被唤醒，获取锁
```

## 11. 常见问题解答

### 11.1 为什么非公平锁性能更好？

非公平锁允许新到达的线程立即尝试获取锁，如果锁恰好被释放，新线程可以"插队"，避免唤醒等待线程的开销。但可能导致线程饥饿。

### 11.2 重入锁是如何实现的？

通过 `state` 字段记录重入次数，并通过 `exclusiveOwnerThread` 记录持有锁的线程。当同一线程再次获取锁时，直接增加 `state` 值，无需CAS。

### 11.3 ReentrantLock 与 synchronized 如何选择？

- **优先使用 synchronized**：代码更简洁，JVM 已深度优化
- **使用 ReentrantLock**：需要公平锁、可中断、超时或条件变量时

### 11.4 如何避免死锁？

1. 多个锁按固定顺序获取
2. 使用超时机制：`tryLock(timeout, unit)`
3. 使用可中断锁：`lockInterruptibly()`

## 12. 总结

ReentrantLock 是 Java 并发编程中的重要工具，其核心特点：

1. **基于 AQS 实现**：利用 CLH 队列管理等待线程
2. **支持公平/非公平模式**：根据场景选择合适的公平性策略
3. **可重入机制**：通过 state 字段实现重入计数
4. **丰富的功能**：支持中断、超时、条件变量等高级特性
5. **性能优化**：使用 CAS、自旋等技术提升性能

理解 ReentrantLock 的工作原理，有助于更好地理解 Java 并发包的整体设计，以及如何在合适的场景下选择和使用锁机制。

