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

package org.apache.dolphinscheduler.server.master.registry;

import static org.apache.dolphinscheduler.common.constants.Constants.SLEEP_TIME_MILLIS;

import org.apache.dolphinscheduler.common.IStoppable;
import org.apache.dolphinscheduler.common.enums.ServerStatus;
import org.apache.dolphinscheduler.common.model.MasterHeartBeat;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.NetUtils;
import org.apache.dolphinscheduler.meter.metrics.BaseServerLoadProtection;
import org.apache.dolphinscheduler.meter.metrics.DefaultMetricsProvider;
import org.apache.dolphinscheduler.meter.metrics.MetricsProvider;
import org.apache.dolphinscheduler.meter.metrics.SystemMetrics;
import org.apache.dolphinscheduler.registry.api.RegistryClient;
import org.apache.dolphinscheduler.registry.api.RegistryException;
import org.apache.dolphinscheduler.registry.api.enums.RegistryNodeType;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.engine.MasterCoordinator;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <p>DolphinScheduler master register client, used to connect to registry and hand the registry events.
 * <p>When the Master node startup, it will register in registry center. And start a {@link MasterHeartBeatTask} to update its metadata in registry.
 */
@Component
@Slf4j
public class MasterRegistryClient implements AutoCloseable {

    @Autowired
    private RegistryClient registryClient;

    @Autowired
    private MasterConfig masterConfig;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private MasterCoordinator masterCoordinator;

    private MasterHeartBeatTask masterHeartBeatTask;

    /**
     * 一、MasterHeartBeatTask通过有参数构造函数对参数赋值原因
     *  1、要点：
     *       a. 注册前需要调用 getHeartBeat() 获取数据
     *       b. 线程启动在注册验证完成后
     *       c. 如果使用 Spring 注入，Bean 会在容器初始化时创建，但此时可能未准备好
     *  2、生命周期需要精确控制
     *       a. 创建时机：在 start() 方法中按需创建
     *       b. 启动时机：注册成功并验证后启动
     *       c.关闭时机：在 close() 中调用 shutdown()
     *  3、如果使用 Spring 管理：
     *       a. Bean 会在容器初始化时创建，时机不对
     *       b. 线程启动时机难以精确控制
     *       c. 关闭需要配合 Spring 的销毁流程
     *  4、与注册流程紧密耦合
     *    心跳任务的创建和启动是注册流程的一部分，需要在 start() 中按顺序执行：
     *      b. 创建心跳任务
     *      c. 获取心跳数据检查负载
     *      d. 注册到注册中心
     *      e. 验证注册成功
     *      f. 启动心跳线程
     */

    public void start() {
        try {
            // 业务层心跳初始化,上报服务监控数据
            this.masterHeartBeatTask =
                    //通过有参数构造函数对参数赋值,为什么不再里面依赖注入呢?
                    // 需要在注册前创建，但延迟启动线程 --》需要先创建心跳任务，获取心跳数据用于负载检查 -》 使用心跳数据注册到注册中心 -》最后才启动心跳线程
                    new MasterHeartBeatTask(masterConfig, metricsProvider, registryClient, masterCoordinator);
            // master registry
            registry();
            // 连接状态监听
            registryClient.addConnectionStateListener(new MasterConnectionStateListener(registryClient));
        } catch (Exception e) {
            throw new RegistryException("Master registry client start up error", e);
        }
    }

    public void setRegistryStoppable(IStoppable stoppable) {
        registryClient.setStoppable(stoppable);
    }

    @Override
    public void close() {
        // TODO unsubscribe MasterRegistryDataListener
        if (masterHeartBeatTask != null) {
            masterHeartBeatTask.shutdown();
        }
        if (registryClient.isConnected()) {
            deregister();
        }
        log.info("Closed MasterRegistryClient");
    }

    /**
     * Registry the current master server itself to registry.
     */
    void registry() {
        log.info("Master node : {} registering to registry center", masterConfig.getMasterAddress());
        // 空间:/dolphinscheduler 路径: /nodes/master/ip:port
        String masterRegistryPath = masterConfig.getMasterRegistryPath();

        /**获取心跳数据(业务服务负载情况数据) {@link DefaultMetricsProvider#getSystemMetrics()} */
        MasterHeartBeat heartBeat = masterHeartBeatTask.getHeartBeat();
        /**
         * 负载保护 如果负载,则每隔1S获取负载数据,直到服务达标
         * {@link MasterConfig#serverLoadProtection}
         * {@link BaseServerLoadProtection#isOverload(SystemMetrics)} 再注册
         */
        while (ServerStatus.BUSY.equals(heartBeat.getServerStatus())) {
            log.warn("Master node is BUSY: {}", heartBeat);
            heartBeat = masterHeartBeatTask.getHeartBeat();
            ThreadUtils.sleep(SLEEP_TIME_MILLIS);
        }

        // remove before persist
        registryClient.remove(masterRegistryPath);
        registryClient.persistEphemeral(masterRegistryPath, JSONUtils.toJsonString(masterHeartBeatTask.getHeartBeat()));
        log.info("Master node persisted to registry path: {}, host: {}", masterRegistryPath, NetUtils.getHost());

        int checkCount = 0;
        // 等待故障转移
        while (!registryClient.checkNodeExists(NetUtils.getHost(), RegistryNodeType.MASTER)) {
            checkCount++;
            log.warn("The current master server node:{} cannot find in registry, check count: {}, registry path: {}", 
                    NetUtils.getHost(), checkCount, masterRegistryPath);
            ThreadUtils.sleep(SLEEP_TIME_MILLIS);
            // Add timeout protection to avoid infinite loop
            if (checkCount > 30) {
                log.error("Master node:{} cannot be found in registry after {} checks, this may indicate a registry synchronization issue. " +
                         "Registry path: {}, master address: {}", NetUtils.getHost(), checkCount, masterRegistryPath, masterConfig.getMasterAddress());
                throw new RegistryException("Master node cannot be found in registry after multiple checks, registry may not be synchronized");
            }
        }
        log.info("Master node:{} found in registry after {} checks", NetUtils.getHost(), checkCount);

        // sleep 1s, waiting master failover remove
        log.info("About to sleep {}ms, waiting for master failover remove", SLEEP_TIME_MILLIS);
        ThreadUtils.sleep(SLEEP_TIME_MILLIS);
        log.info("Finished waiting for master failover remove, about to start heartbeat task");

        log.info("About to start masterHeartBeatTask, masterHeartBeatTask is null: {}", masterHeartBeatTask == null);
        masterHeartBeatTask.start();
        log.info("Master node : {} registered to registry center successfully", masterConfig.getMasterAddress());

    }

    public void deregister() {
        try {
            registryClient.remove(masterConfig.getMasterRegistryPath());
            log.info("Master node : {} unRegistry to register center.", masterConfig.getMasterAddress());
            if (masterHeartBeatTask != null) {
                masterHeartBeatTask.shutdown();
            }
            registryClient.close();
        } catch (Exception e) {
            log.error("MasterServer remove registry path exception ", e);
        }
    }

    public boolean isAvailable() {
        return registryClient.isConnected();
    }
}
