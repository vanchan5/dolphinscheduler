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

package org.apache.dolphinscheduler.server.master.cluster;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.dolphinscheduler.plugin.registry.zookeeper.ZookeeperTreeCacheListenerAdapter;
import org.apache.dolphinscheduler.server.master.engine.system.SystemEventBus;
import org.apache.dolphinscheduler.server.master.engine.system.SystemEventBusFireWorker;
import org.apache.dolphinscheduler.server.master.engine.system.event.ISystemEventHandler;
import org.apache.dolphinscheduler.server.master.engine.system.event.MasterFailoverEvent;
import org.apache.dolphinscheduler.server.master.engine.system.event.MasterFailoverEventHandler;
import org.apache.dolphinscheduler.server.master.engine.system.event.WorkerFailoverEvent;

import java.util.Date;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 设计要点
 * 1、关注点分离：ClusterStateMonitors 仅负责监控与事件发布，不直接处理故障转移
 * 2、延迟机制：30 秒延迟避免误触发
 * 3、事件驱动：使用 SystemEventBus 解耦监控与处理
 * 4、线程安全：使用 CopyOnWriteArrayList 和同步块
 * 与其他组件的关系
 * 1、ClusterManager：提供 MasterClusters 和 WorkerClusters
 * 2、MasterClusters/WorkerClusters：维护节点列表并通知监听器
 * 3、SystemEventBus：异步事件总线
 * 4、FailoverCoordinator：实际执行故障转移逻辑
 *
 * 该机制实现了集群节点的实时监控和自动故障转移，提升了系统的高可用性
 */
@Slf4j
@Component
public class ClusterStateMonitors {

    @Autowired
    private ClusterManager clusterManager;

    @Autowired
    private SystemEventBus systemEventBus;

    public void start() {
        /**
         * 注册监听器
         *
         * 注册中心检测节点移除 {@link ZookeeperTreeCacheListenerAdapter#childEvent(CuratorFramework, TreeCacheEvent)}
         *   ↓
         * RegistryClient 触发订阅事件 (TreeCacheListener)
         *   ↓
         * AbstractClusterSubscribeListener.notify() 收到 REMOVE 事件
         *   ↓
         * MasterClusters.onServerRemove() 被调用
         *   ↓
         * 遍历所有监听器，调用 listener.onServerRemove(masterServer)
         *   ↓
         * ClusterStateMonitors.masterRemoved() 被触发
         *   ↓
         * 发布 MasterFailoverEvent 到 SystemEventBus
         */
        clusterManager.getMasterClusters()
                .registerListener((IClusters.ServerRemovedListener<MasterServerMetadata>) this::masterRemoved);
        clusterManager.getWorkerClusters()
                .registerListener((IClusters.ServerRemovedListener<WorkerServerMetadata>) this::workerRemoved);
        log.info("ClusterStateMonitors started...");
    }

    /**
     * 30秒延迟机制：如果节点在30秒内重新连接，则跳过故障转移
     * 发布 {@link MasterFailoverEvent} 事件 ->
     * 启动故障转移 {@link SystemEventBusFireWorker#start()} ->
     * 匹配 {@link ISystemEventHandler#matchState()} ->
     * 故障转移处理 {@link MasterFailoverEventHandler#handle(MasterFailoverEvent)}
     *
     *
     * @param masterServer
     */
    void masterRemoved(final MasterServerMetadata masterServer) {
        // We set a delay of 30 seconds for the master failover event
        // If the master can reconnect to registry within 30 seconds, the master will skip failover.
        systemEventBus.publish(MasterFailoverEvent.of(masterServer, new Date(), 30_000));
    }

    void workerRemoved(final WorkerServerMetadata workerServer) {
        // We set a delay of 30 seconds for the worker failover event
        // If the worker can reconnect to registry within 30 seconds, the worker will skip failover.
        systemEventBus.publish(WorkerFailoverEvent.of(workerServer, new Date(), 30_000));
    }

}
