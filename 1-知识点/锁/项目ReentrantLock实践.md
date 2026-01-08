# DolphinScheduler 项目中 ReentrantLock 实践详细分析

## 概述

本文档详细分析 DolphinScheduler 项目中 `ReentrantLock` 的使用场景和实践。虽然项目中没有涉及递归、嵌套或回调调用 lock 的场景，但 `ReentrantLock` 仍然被广泛使用，主要原因是其提供了比 `synchronized` 更灵活的功能，特别是与 `Condition` 结合使用时。

## 为什么使用 ReentrantLock（即使没有递归/嵌套/回调）

虽然项目中没有递归调用 lock 的场景，但选择 `ReentrantLock` 而非 `synchronized` 的原因包括：

1. **Condition 支持**：`ReentrantLock` 可以创建多个 `Condition` 对象，实现更精细的线程等待/唤醒控制
2. **可中断的锁获取**：支持 `lockInterruptibly()`，可以响应中断
3. **公平锁选项**：可以创建公平锁（虽然项目中未使用）
4. **更灵活的锁控制**：可以尝试获取锁（`tryLock()`），支持超时获取锁
5. **更好的性能**：在高竞争场景下，`ReentrantLock` 的性能通常优于 `synchronized`

## 使用场景分析

### 场景一：TaskExecutorLifecycleEventRemoteReporter - 事件通道等待/唤醒机制

**类路径**：`org.apache.dolphinscheduler.task.executor.eventbus.TaskExecutorLifecycleEventRemoteReporter`

#### 1.1 场景描述

这是一个任务执行器生命周期事件的远程报告器，负责将任务执行器的生命周期事件异步报告给 Master。它使用守护线程定期轮询事件通道，处理需要发送的事件。

#### 1.2 代码实现

```java
// 事件通道操作的锁
private final Lock eventChannelsLock = new ReentrantLock();

// 事件通道为空的条件
private final Condition taskExecutionEventEmptyCondition = eventChannelsLock.newCondition();

// 事件通道映射表
private final Map<Integer, ReportableTaskExecutorLifecycleEventChannel> eventChannels = new ConcurrentHashMap<>();
```

#### 1.3 核心方法分析

##### 1.3.1 报告事件（添加事件到通道）

```java
@Override
public void reportTaskExecutorLifecycleEvent(final IReportableTaskExecutorLifecycleEvent reportableTaskExecutorLifecycleEvent) {
    eventChannelsLock.lock();
    try {
        log.debug("Report : {}", JSONUtils.toPrettyJsonString(reportableTaskExecutorLifecycleEvent));
        int taskInstanceId = reportableTaskExecutorLifecycleEvent.getTaskInstanceId();
        // 根据任务实例ID获取或创建对应的事件通道
        eventChannels.computeIfAbsent(
                taskInstanceId,
                k -> new ReportableTaskExecutorLifecycleEventChannel(taskInstanceId))
                .addTaskExecutionEvent(reportableTaskExecutorLifecycleEvent);
        // 唤醒等待的守护线程
        taskExecutionEventEmptyCondition.signalAll();
    } finally {
        eventChannelsLock.unlock();
    }
}
```

**作用**：
- 保护 `eventChannels` 的并发访问（虽然使用 `ConcurrentHashMap`，但需要与 `Condition` 配合使用）
- 在添加事件后调用 `signalAll()` 唤醒等待的守护线程

##### 1.3.2 等待所有通道为空

```java
private void tryToWaitIfAllTaskExecutionEventChannelEmpty() throws InterruptedException {
    eventChannelsLock.lock();
    while (isAllTaskExecutorEventChannelEmpty()) {
        taskExecutionEventEmptyCondition.await();
        // await() 会自动释放锁，被唤醒后重新获取锁
    }
    eventChannelsLock.unlock();
}
```

**作用**：
- 当所有事件通道都为空时，守护线程进入等待状态，避免忙等待（busy-waiting）
- 使用 `await()` 自动释放锁，允许其他线程添加事件
- 被唤醒后自动重新获取锁，继续执行

**执行流程**：
1. 获取锁 `lock()`
2. 检查所有通道是否为空
3. 如果为空，调用 `await()` 进入等待（自动释放锁）
4. 其他线程添加事件时调用 `signalAll()` 唤醒
5. 被唤醒后重新获取锁（在 `await()` 内部完成）
6. 重新检查条件，退出循环
7. 释放锁 `unlock()`

##### 1.3.3 等待重试间隔

```java
private void waitIfAnyTaskExecutionEventChannelRetryIntervalPassed() throws InterruptedException {
    eventChannelsLock.lock();
    try {
        final long waitInterval =
                (getOldestReportTime() + DEFAULT_TASK_EXECUTOR_EVENT_RETRY_INTERVAL) - System.currentTimeMillis();
        if (waitInterval <= 0) {
            return;
        }
        taskExecutionEventEmptyCondition.await(waitInterval, TimeUnit.MILLISECONDS);
    } finally {
        eventChannelsLock.unlock();
    }
}
```

**作用**：
- 根据最老事件的发送时间计算等待时间，避免频繁轮询
- 如果重试间隔未到，等待到最早可以重试的时间点
- 如果等待期间有新事件到达（`signalAll()`），会被提前唤醒

**等待时间计算示例**：
```
假设：
- 当前时间：2024-01-01 10:00:00 (时间戳: 1704067200000)
- 最老事件发送时间：2024-01-01 09:58:00 (时间戳: 1704067080000)
- 重试间隔：3分钟 (180000 毫秒)

计算：
waitInterval = (1704067080000 + 180000) - 1704067200000
             = 1704067260000 - 1704067200000
             = 60000 毫秒 (1分钟)

结果：等待 1 分钟后，该事件可以重试
```

##### 1.3.4 接收 ACK（移除已确认的事件）

```java
@Override
public void receiveTaskExecutorLifecycleEventACK(final TaskExecutorLifecycleEventAck eventAck) {
    final int taskExecutorId = eventAck.getTaskExecutorId();
    eventChannelsLock.lock();
    try {
        final ReportableTaskExecutorLifecycleEventChannel eventChannel = eventChannels.get(taskExecutorId);
        if (eventChannel == null) {
            return;
        }
        final IReportableTaskExecutorLifecycleEvent removed =
                eventChannel.remove(eventAck.getTaskExecutorLifecycleEventType());
        if (removed != null) {
            log.info("Success removed {} by ack: {}", removed, eventAck);
        }
        if (eventChannel.isEmpty()) {
            eventChannels.remove(taskExecutorId);
            log.debug("Removed ReportableTaskExecutorLifecycleEventChannel: {}", taskExecutorId);
        }
        taskExecutionEventEmptyCondition.signalAll();
    } finally {
        eventChannelsLock.unlock();
    }
}
```

**作用**：
- 保护事件通道的移除操作
- 移除事件后唤醒等待的守护线程

#### 1.4 为什么使用 ReentrantLock + Condition

1. **避免忙等待**：使用 `Condition.await()` 让线程进入 WAITING 状态，而不是循环检查（忙等待）
2. **精确的等待控制**：可以精确控制等待时间（`await(timeout, unit)`）
3. **多条件支持**：虽然这里只使用了一个 Condition，但 `ReentrantLock` 支持创建多个 Condition
4. **自动锁管理**：`await()` 会自动释放锁，被唤醒后自动重新获取锁

#### 1.5 设计优势

- ✅ **CPU 友好**：避免忙等待，在需要等待时让线程进入 WAITING 状态
- ✅ **精确调度**：根据实际需要等待的时间，精确计算等待时间
- ✅ **及时响应**：一旦有新事件或重试间隔到达，立即被唤醒并处理
- ✅ **资源节约**：在无事件时完全释放 CPU，让其他线程使用

---

### 场景二：TaskExecutorWorker - 任务执行器等待/唤醒机制

**类路径**：`org.apache.dolphinscheduler.task.executor.worker.TaskExecutorWorker`

#### 2.1 场景描述

`TaskExecutorWorker` 负责管理和执行任务执行器。它维护两个映射表：
- `registeredTaskExecutors`：已注册的任务执行器
- `activeTaskExecutors`：正在执行的任务执行器

主循环会定期检查所有活跃的任务执行器，跟踪它们的状态。

#### 2.2 代码实现

```java
private final Map<Integer, ITaskExecutor> registeredTaskExecutors = new ConcurrentHashMap<>();
private final Map<Integer, ITaskExecutor> activeTaskExecutors = new ConcurrentHashMap<>();

private final Lock activeTaskExecutorsChangeLock = new ReentrantLock();
private final Condition activeTaskExecutorEmptyCondition = activeTaskExecutorsChangeLock.newCondition();
```

#### 2.3 核心方法分析

##### 2.3.1 主循环（start 方法）

```java
@Override
public void start() {
    while (true) {
        long minNextTrackDelay = 100;
        // 遍历所有活跃的任务执行器，跟踪它们的状态
        for (final ITaskExecutor taskExecutor : activeTaskExecutors.values()) {
            try (final MDCAutoClosable closable = TaskExecutorMDCUtils.logWithMDC(taskExecutor)) {
                try {
                    if (!taskExecutor.isStarted()) {
                        taskExecutor.start();
                    }
                    final long remainingTrackDelay = taskExecutor.getRemainingTrackDelay();
                    if (remainingTrackDelay > 0) {
                        minNextTrackDelay = Math.min(minNextTrackDelay, remainingTrackDelay);
                    } else {
                        trackTaskExecutorState(taskExecutor);
                    }
                } catch (Throwable e) {
                    log.error("{} execute failed", taskExecutor, e);
                    onTaskExecutorFailed(taskExecutor);
                }
            }
        }

        activeTaskExecutorsChangeLock.lock();
        try {
            if (activeTaskExecutors.isEmpty()) {
                // 如果所有任务执行器都已完成，无限期等待直到有新任务
                activeTaskExecutorEmptyCondition.await();
            } else {
                // 如果有活跃任务，等待到最早需要跟踪的时间点
                activeTaskExecutorEmptyCondition.await(minNextTrackDelay, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            log.info("TaskExecutorWorker(id={}) is interrupted", workerId, e);
            break;
        } finally {
            activeTaskExecutorsChangeLock.unlock();
        }
    }
}
```

**作用**：
- 定期检查所有活跃的任务执行器状态
- 如果所有任务执行器都已完成，等待直到有新任务被激活
- 如果有活跃任务，等待到最早需要跟踪的时间点

##### 2.3.2 激活任务执行器（fireTaskExecutor）

```java
@Override
public void fireTaskExecutor(final ITaskExecutor taskExecutor) {
    activeTaskExecutorsChangeLock.lock();
    try {
        final Integer taskExecutorId = taskExecutor.getId();
        if (!registeredTaskExecutors.containsKey(taskExecutorId)) {
            throw new IllegalStateException(
                    "The TaskExecutorWorker has not registered " + taskExecutor);
        }
        if (activeTaskExecutors.containsKey(taskExecutorId)) {
            throw new IllegalStateException(
                    "The TaskExecutorWorker has already fired " + taskExecutor);
        }
        activeTaskExecutors.put(taskExecutorId, taskExecutor);
        // 唤醒等待的主循环线程
        activeTaskExecutorEmptyCondition.signalAll();
    } finally {
        activeTaskExecutorsChangeLock.unlock();
    }
}
```

**作用**：
- 将任务执行器从注册表移动到活跃表
- 唤醒等待的主循环线程，立即开始跟踪新任务

##### 2.3.3 取消激活任务执行器（unFireTaskExecutor）

```java
@Override
public void unFireTaskExecutor(ITaskExecutor taskExecutor) {
    try {
        activeTaskExecutorsChangeLock.lock();
        final Integer taskExecutorId = taskExecutor.getId();
        activeTaskExecutors.remove(taskExecutorId);
    } finally {
        activeTaskExecutorsChangeLock.unlock();
    }
}
```

**作用**：
- 从活跃表中移除任务执行器（任务完成或失败时调用）
- 注意：这里没有调用 `signalAll()`，因为主循环会定期检查，不需要立即唤醒

#### 2.4 为什么使用 ReentrantLock + Condition

1. **避免忙等待**：当没有活跃任务时，主循环线程进入等待状态，而不是持续轮询
2. **精确的等待时间**：根据最早需要跟踪的时间点计算等待时间，避免不必要的轮询
3. **及时响应**：一旦有新任务被激活，立即唤醒主循环线程

#### 2.5 设计优势

- ✅ **资源节约**：无任务时线程进入等待状态，不消耗 CPU
- ✅ **精确调度**：根据任务的实际跟踪延迟，精确计算等待时间
- ✅ **及时处理**：新任务到达时立即被唤醒并处理

---

### 场景三：NettyRemotingClient - 双重检查锁定模式

**类路径**：`org.apache.dolphinscheduler.extract.base.client.NettyRemotingClient`

#### 3.1 场景描述

`NettyRemotingClient` 是 Netty RPC 客户端，负责管理与服务端的连接。它维护一个 `Channel` 映射表，每个 `Host` 对应一个 `Channel`。为了避免重复创建 `Channel`，使用双重检查锁定模式。

#### 3.2 代码实现

```java
private final ReentrantLock channelsLock = new ReentrantLock();
private final Map<Host, Channel> channels = new ConcurrentHashMap<>();
```

#### 3.3 核心方法分析

##### 3.3.1 获取或创建 Channel（双重检查锁定）

```java
Channel getOrCreateChannel(Host host) {
    // 第一次检查（无锁，快速路径）
    Channel channel = channels.get(host);
    if (channel != null && channel.isActive()) {
        return channel;
    }
    try {
        channelsLock.lock();
        // 第二次检查（有锁，避免重复创建）
        channel = channels.get(host);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        // 创建新 Channel
        channel = createChannel(host);
        channels.put(host, channel);
    } finally {
        channelsLock.unlock();
    }
    return channel;
}
```

**执行流程**：

```
线程A                         线程B                         线程C
  |                             |                             |
  |-- 第一次检查（无锁）         |-- 第一次检查（无锁）         |-- 第一次检查（无锁）
  |-- channels.get(host)        |-- channels.get(host)        |-- channels.get(host)
  |-- 返回 null                 |-- 返回 null                 |-- 返回 null
  |                             |                             |
  |-- 尝试获取锁                |-- 尝试获取锁（等待）         |-- 尝试获取锁（等待）
  |-- channelsLock.lock() ✅    |-- channelsLock.lock() ⏳    |-- channelsLock.lock() ⏳
  |                             |                             |
  |-- 第二次检查（有锁）         |                             |
  |-- channels.get(host)        |                             |
  |-- 仍然是 null               |                             |
  |                             |                             |
  |-- createChannel(host) 🆕    |                             |
  |-- channels.put(host, ch)   |                             |
  |-- 释放锁                    |                             |
  |-- channelsLock.unlock()     |                             |
  |                             |                             |
  |                             |-- 获得锁 ✅                  |
  |                             |-- 第二次检查（有锁）          |
  |                             |-- channels.get(host) ✅      |
  |                             |-- 返回已存在的 channel       |
  |                             |-- 释放锁                     |
  |                             |                             |
  |                             |                             |-- 获得锁 ✅
  |                             |                             |-- 第二次检查（有锁）
  |                             |                             |-- channels.get(host) ✅
  |                             |                             |-- 返回已存在的 channel
```

**关键点**：

1. **为什么需要两次检查**：
   - 第一次检查（无锁）：快速路径，大多数情况下 Channel 已存在，直接返回，避免锁开销
   - 第二次检查（有锁）：避免在获取锁期间，其他线程已创建并放入 Channel，防止重复创建

2. **为什么使用 ConcurrentHashMap**：
   - `channels.get()` 是线程安全的，可以在无锁环境下进行第一次检查
   - 但写入操作（`channels.put()`）仍需要同步，确保原子性

3. **为什么使用 ReentrantLock 而非 synchronized**：
   - 虽然这里没有使用 Condition，但 `ReentrantLock` 提供了更灵活的锁控制
   - 可以尝试获取锁（`tryLock()`），支持超时获取锁
   - 性能在高竞争场景下通常优于 `synchronized`

##### 3.3.2 关闭所有 Channel

```java
private void closeChannels() {
    try {
        channelsLock.lock();
        channels.values().forEach(Channel::close);
        channels.clear();
    } finally {
        channelsLock.unlock();
    }
}
```

**作用**：
- 保护关闭操作，确保所有 Channel 都被正确关闭
- 清空映射表

#### 3.4 为什么使用 ReentrantLock

1. **双重检查锁定模式**：需要显式锁控制，`ReentrantLock` 提供了更灵活的锁管理
2. **性能考虑**：在高并发场景下，`ReentrantLock` 的性能通常优于 `synchronized`
3. **可扩展性**：如果未来需要添加 Condition 或其他高级功能，`ReentrantLock` 更容易扩展

#### 3.5 设计优势

- ✅ **性能优化**：第一次检查无锁，避免不必要的锁竞争
- ✅ **线程安全**：双重检查确保不会重复创建 Channel
- ✅ **资源节约**：复用已存在的 Channel，避免频繁创建和销毁

---

## 总结

### 使用 ReentrantLock 的常见模式

1. **ReentrantLock + Condition**：实现线程等待/唤醒机制
   - 场景：`TaskExecutorLifecycleEventRemoteReporter`、`TaskExecutorWorker`
   - 优势：避免忙等待，精确控制等待时间

2. **ReentrantLock + 双重检查锁定**：保护资源创建
   - 场景：`NettyRemotingClient`
   - 优势：性能优化，避免重复创建资源

### 为什么选择 ReentrantLock 而非 synchronized

虽然项目中没有递归/嵌套/回调调用 lock 的场景，但选择 `ReentrantLock` 的原因包括：

1. **Condition 支持**：`ReentrantLock` 可以创建多个 `Condition` 对象，实现更精细的线程等待/唤醒控制
2. **可中断的锁获取**：支持 `lockInterruptibly()`，可以响应中断
3. **更灵活的锁控制**：可以尝试获取锁（`tryLock()`），支持超时获取锁
4. **更好的性能**：在高竞争场景下，`ReentrantLock` 的性能通常优于 `synchronized`
5. **可扩展性**：如果未来需要添加 Condition 或其他高级功能，`ReentrantLock` 更容易扩展

### 最佳实践

1. **总是使用 try-finally**：确保锁被正确释放
2. **使用 Condition 避免忙等待**：在需要等待时使用 `await()` 而非循环检查
3. **双重检查锁定模式**：在创建资源时使用双重检查，避免重复创建
4. **精确的等待时间**：根据实际需要计算等待时间，避免不必要的轮询
5. **及时唤醒**：在条件满足时及时调用 `signalAll()` 唤醒等待的线程

### 注意事项

1. **死锁风险**：虽然项目中没有递归调用，但仍需注意避免死锁
2. **锁粒度**：保持锁的粒度尽可能小，避免长时间持有锁
3. **异常处理**：确保在异常情况下也能正确释放锁
4. **性能监控**：监控锁竞争情况，必要时优化锁的使用

