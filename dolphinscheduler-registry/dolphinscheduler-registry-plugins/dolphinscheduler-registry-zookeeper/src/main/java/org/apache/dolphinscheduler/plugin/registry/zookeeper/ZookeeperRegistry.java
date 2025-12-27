/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.plugin.registry.zookeeper;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import org.apache.dolphinscheduler.registry.api.*;

import org.apache.commons.lang3.time.DurationUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import com.google.common.base.Strings;

/**
 * 配置驱动: registry.type=zookeeper
 *
 * {@link ZookeeperRegistryAutoConfiguration#zookeeperRegistry(ZookeeperRegistryProperties)} 注册 Registry bean
 * ->
 * {@link RegistryConfiguration#registryClient(Registry)} 根据Registry bean注册registryClient,初始化registryClient
 *
 */
@Slf4j
final class ZookeeperRegistry implements Registry {

    private final ZookeeperRegistryProperties.ZookeeperProperties properties;
    private final CuratorFramework client;

    private final Map<String, TreeCache> treeCacheMap = new ConcurrentHashMap<>();

    private static final ThreadLocal<Map<String, InterProcessMutex>> threadLocalLockMap = new ThreadLocal<>();

    /**
     * 注册Registry为bean的时候{@link ZookeeperRegistryAutoConfiguration#zookeeperRegistry(ZookeeperRegistryProperties)}
     * 初始化ZookeeperRegistry
     *
     * @param registryProperties
     */
    ZookeeperRegistry(ZookeeperRegistryProperties registryProperties) {
        properties = registryProperties.getZookeeper();
        /**
         * 创建指数退避重试策略
         * 参数说明：
         * baseSleepTimeMs：初始等待时间（默认 1 秒）
         * maxRetries：最大重试次数（默认 3 次）
         * maxSleepMs：最大等待时间（默认 3 秒）
         * 重试间隔按指数增长，直到达到 maxSleepMs,提高网络不稳定时的成功率
         *
         * 重试次数	计算公式	                计算值	实际等待时间	    说明
         * 第1次重试	1000ms × 2⁰ = 1000ms	1000ms	1秒	        初始等待时间
         * 第2次重试	1000ms × 2¹ = 2000ms	2000ms	2秒	        指数增长
         * 第3次重试	1000ms × 2² = 4000ms	4000ms	3秒	        达到 maxSleep，被限制为 3秒
         * 第4次重试	-	                        -	3秒	        继续保持 maxSleep
         * 第5次重试	-	                        -	3秒	        继续保持 maxSleep
         *
         * 公式：waitTime = baseSleepTime × 2^(retryCount-1)
         * 限制：实际等待时间 = min(计算值, maxSleepMs)
         * 作用：避免频繁重试，并防止等待时间过长
         */
        final ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(
                (int) properties.getRetryPolicy().getBaseSleepTime().toMillis(),
                properties.getRetryPolicy().getMaxRetries(),
                (int) properties.getRetryPolicy().getMaxSleep().toMillis());

        CuratorFrameworkFactory.Builder builder =
                CuratorFrameworkFactory.builder()
                        /**
                         * 只维护一个活跃连接（通常是最先连接成功的）
                         * 但通过以下机制实现高可用：
                         *  a.服务器列表：提供多个备用服务器
                         *  b.自动故障转移：连接失败时自动切换
                         *  c.重试策略：使用 ExponentialBackoffRetry 进行重试
                         *  d.会话保持：通过 ZooKeeper 的会话机制，在连接断开后的一段时间内（sessionTimeout）保持会话，重连后可以恢复
                         */
                        .connectString(properties.getConnectString())//IP1:PORT1,IP2:PORT2
                        .retryPolicy(retryPolicy)
                        .namespace(properties.getNamespace())//命名空间,默认dolphinscheduler
                        .sessionTimeoutMs(DurationUtils.toMillisInt(properties.getSessionTimeout()))//会话超时（默认 60 秒），连接断开后会话保持时长
                        .connectionTimeoutMs(DurationUtils.toMillisInt(properties.getConnectionTimeout()));//连接超时（默认 15 秒），建立连接的最大等待时间

        // 在 YAML 中，~ 表示 null（空值）
        final String digest = properties.getDigest();//启用权限认证
        if (!Strings.isNullOrEmpty(digest)) {
            builder.authorization("digest", digest.getBytes(StandardCharsets.UTF_8))//使用 Digest 认证（格式如 username:password）
                    .aclProvider(new ACLProvider() {//提供 ACL 规则

                        @Override
                        public List<ACL> getDefaultAcl() {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;//返回默认 ACL 列表
                        }

                        @Override
                        public List<ACL> getAclForPath(final String path) {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;//返回指定路径的 ACL 列表
                        }
                    });
        }
        client = builder.build();//构建 CuratorFramework 实例（此时未连接）
    }

    /**
     * 注册Registry为bean的时候{@link ZookeeperRegistryAutoConfiguration#zookeeperRegistry(ZookeeperRegistryProperties)}
     * 启动CuratorFramework客户端
     */
    @Override
    public void start() {
        final StopWatch stopWatch = StopWatch.createStarted();
        //异步非阻塞调用,后台开始连接过程，不会等待连接成功就返回,需要在后续使用 blockUntilConnected() 同步等待连接建立
        client.start();
        try {
            if (!client.blockUntilConnected(DurationUtils.toMillisInt(properties.getBlockUntilConnected()),
                    MILLISECONDS)) {
                client.close();
                throw new RegistryException(
                        "zookeeper connect failed to: " + properties.getConnectString() + " in : "
                                + properties.getBlockUntilConnected() + "ms");
            }
            stopWatch.stop();
            log.info("ZookeeperRegistry started at: {}/ms", stopWatch.getTime());
        } catch (InterruptedException e) {
            //捕获 InterruptedException 后应恢复标志,保持中断状态：让上层代码知道线程被中断
            Thread.currentThread().interrupt();
            throw new RegistryException("Zookeeper registry start failed", e);
        }
    }

    /**
     * 添加连接状态监听器
     * <p>
     * 该方法用于监听 CuratorFramework 客户端与 ZooKeeper 服务器之间的连接状态变化。
     * 当连接状态发生变化时，会通过 {@link ConnectionListener#onUpdate(ConnectionState)} 回调通知上层应用。
     * <p>
     * 工作原理：
     * 1. 通过 {@code client.getConnectionStateListenable()} 获取 CuratorFramework 的连接状态监听器管理器
     * 2. 创建 {@link ZookeeperConnectionStateListener} 适配器，将 Curator 的连接状态转换为
     *    DolphinScheduler 统一的 {@link ConnectionState} 枚举
     * 3. 注册适配器到 CuratorFramework，当连接状态变化时，Curator 会调用适配器的
     *    {@code stateChanged()} 方法，适配器再调用上层的 {@code listener.onUpdate()} 方法
     * <p>
     * 连接状态说明：
     * - CONNECTED: 首次连接成功或重新连接成功
     * - SUSPENDED: 连接挂起（网络临时中断，但会话未超时，可能自动恢复）
     * - RECONNECTED: 从 SUSPENDED 状态恢复连接
     * - DISCONNECTED: 连接丢失（会话超时或长时间无法连接，需要重新建立会话）
     * <p>
     * 使用场景：
     * - Master/Worker 服务启动时注册监听器，监控与注册中心的连接状态
     * - 当连接断开（DISCONNECTED）时，停止服务以避免提供不可靠的服务
     * - 当重新连接（RECONNECTED）时，可能需要重新注册服务节点
     * <p>
     * 线程安全：
     * - {@code addListener()} 方法是线程安全的
     * - {@code stateChanged()} 可能在不同线程中调用，确保 listener 的实现是线程安全的
     * <p>
     * 最佳实践：
     * - 建议在 {@link #start()} 方法调用之后再注册监听器，以确保不会错过初始连接事件
     *
     * @param listener 连接状态监听器，当连接状态变化时会调用其 {@code onUpdate()} 方法
     * @see ZookeeperConnectionStateListener 适配器实现，负责状态转换
     * @see ConnectionState 连接状态枚举
     * @see ConnectionListener 连接状态监听接口
     */
    @Override
    public void addConnectionStateListener(ConnectionListener listener) {
        client.getConnectionStateListenable().addListener(new ZookeeperConnectionStateListener(listener));
    }

    @Override
    public void connectUntilTimeout(@NonNull Duration timeout) throws RegistryException {
        try {
            if (!client.blockUntilConnected(DurationUtils.toMillisInt(timeout), MILLISECONDS)) {
                throw new RegistryException(
                        String.format("Cannot connect to registry in %s s", timeout.getSeconds()));
            }
        } catch (RegistryException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryException(
                    String.format("Cannot connect to registry in %s s", timeout.getSeconds()), e);
        }
    }

    @Override
    public void subscribe(final String path, final SubscribeListener listener) {
        final TreeCache treeCache = treeCacheMap.computeIfAbsent(path, $ -> new TreeCache(client, path));
        treeCache.getListenable().addListener(new ZookeeperTreeCacheListenerAdapter(path, listener));
        try {
            treeCache.start();
        } catch (Exception e) {
            treeCacheMap.remove(path);
            throw new RegistryException("Failed to subscribe listener for key: " + path, e);
        }
    }

    @Override
    public String get(String key) {
        try {
            return new String(client.getData().forPath(key), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RegistryException("zookeeper get data error", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return null != client.checkExists().forPath(key);
        } catch (Exception e) {
            throw new RegistryException("zookeeper check key is existed error", e);
        }
    }

    /**
     * key (String): 节点路径
     *      必须是有效的 ZooKeeper 路径
     *      路径会根据配置的 namespace 自动拼接前缀
     *
     * value (String): 节点存储的数据
     *      字符串类型，内部转换为 UTF-8 字节数组存储
     *      不能为 null
     *
     * deleteOnDisconnect (boolean): 是否在断开连接时自动删除节点
     *      true: 创建临时节点（EPHEMERAL），客户端断开连接后节点自动删除
     *      false: 创建持久节点（PERSISTENT），节点会永久存在直到手动删除
     * @param key                the key, cannot be null
     * @param value              the value, cannot be null
     * @param deleteOnDisconnect if true, when the connection state is disconnected, the key will be deleted
     */
    @Override
    public void put(String key, String value, boolean deleteOnDisconnect) {
        final CreateMode mode = deleteOnDisconnect ? CreateMode.EPHEMERAL : CreateMode.PERSISTENT;

        try {
            client.create()
                    // 如果节点存在则更新数据，不存在则创建
                    .orSetData()
                    // 如果父节点不存在则自动创建
                    .creatingParentsIfNeeded()
                    // 设置节点类型：EPHEMERAL 或 PERSISTENT
                    .withMode(mode)
                    .forPath(key, value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RegistryException("Failed to put registry key: " + key, e);
        }
    }

    @Override
    public List<String> children(String key) {
        try {
            List<String> result = client.getChildren().forPath(key);
            result.sort(Comparator.reverseOrder());
            return result;
        } catch (Exception e) {
            throw new RegistryException("zookeeper get children error", e);
        }
    }

    @Override
    public void delete(String nodePath) {
        try {
            client.delete()
                    // 递归删除所有子节点
                    .deletingChildrenIfNeeded()
                    .forPath(nodePath);
        } catch (KeeperException.NoNodeException ignored) {
            // Is already deleted or does not exist,节点不存在时，静默处理，不抛出异常
        } catch (Exception e) {
            throw new RegistryException("Failed to delete registry key: " + nodePath, e);
        }
    }

    @Override
    public boolean acquireLock(String key) {
        Map<String, InterProcessMutex> processMutexMap = threadLocalLockMap.get();
        if (null == processMutexMap) {
            processMutexMap = new HashMap<>();
            threadLocalLockMap.set(processMutexMap);
        }
        InterProcessMutex interProcessMutex = null;
        try {
            interProcessMutex =
                    Optional.ofNullable(processMutexMap.get(key)).orElse(new InterProcessMutex(client, key));
            if (interProcessMutex.isAcquiredInThisProcess()) {
                // Since etcd/jdbc cannot implement a reentrant lock, we need to check if the lock is already acquired
                // If it is already acquired, return true directly
                // This means you only need to release once when you acquire multiple times
                return true;
            }
            interProcessMutex.acquire();
            processMutexMap.put(key, interProcessMutex);
            return true;
        } catch (Exception e) {
            try {
                if (interProcessMutex != null) {
                    interProcessMutex.release();
                }
                throw new RegistryException(String.format("zookeeper get lock: %s error", key), e);
            } catch (Exception exception) {
                throw new RegistryException(String.format("zookeeper get lock: %s error", key), e);
            }
        }
    }

    @Override
    public boolean acquireLock(String key, long timeout) {
        Map<String, InterProcessMutex> processMutexMap = threadLocalLockMap.get();
        if (null == processMutexMap) {
            processMutexMap = new HashMap<>();
            threadLocalLockMap.set(processMutexMap);
        }
        InterProcessMutex interProcessMutex = null;
        try {
            interProcessMutex =
                    Optional.ofNullable(processMutexMap.get(key)).orElse(new InterProcessMutex(client, key));
            if (interProcessMutex.isAcquiredInThisProcess()) {
                return true;
            }
            if (interProcessMutex.acquire(timeout, MILLISECONDS)) {
                processMutexMap.put(key, interProcessMutex);
                return true;
            }
            return false;
        } catch (Exception e) {
            try {
                if (interProcessMutex != null) {
                    interProcessMutex.release();
                }
                throw new RegistryException(String.format("zookeeper get lock: %s error", key), e);
            } catch (Exception exception) {
                throw new RegistryException(String.format("zookeeper get lock: %s error", key), e);
            }
        }
    }

    @Override
    public boolean releaseLock(String key) {
        Map<String, InterProcessMutex> processMutexMap = threadLocalLockMap.get();
        if (processMutexMap == null) {
            return true;
        }
        InterProcessMutex interProcessMutex = processMutexMap.get(key);
        if (null == interProcessMutex) {
            return false;
        }
        try {
            interProcessMutex.release();
            processMutexMap.remove(key);
            if (processMutexMap.isEmpty()) {
                threadLocalLockMap.remove();
            }
        } catch (Exception e) {
            throw new RegistryException("zookeeper release lock error", e);
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        return client.getZookeeperClient().isConnected();
    }

    @Override
    public void close() {
        treeCacheMap.values().forEach(CloseableUtils::closeQuietly);
        CloseableUtils.closeQuietly(client);
    }
}
