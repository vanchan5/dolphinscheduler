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

package org.apache.dolphinscheduler.server.master;

import org.apache.dolphinscheduler.common.CommonConfiguration;
import org.apache.dolphinscheduler.common.IStoppable;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.common.lifecycle.ServerLifeCycleManager;
import org.apache.dolphinscheduler.common.thread.DefaultUncaughtExceptionHandler;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.dao.DaoConfiguration;
import org.apache.dolphinscheduler.meter.metrics.MetricsProvider;
import org.apache.dolphinscheduler.meter.metrics.SystemMetrics;
import org.apache.dolphinscheduler.plugin.datasource.api.plugin.DataSourceProcessorProvider;
import org.apache.dolphinscheduler.plugin.storage.api.StorageConfiguration;
import org.apache.dolphinscheduler.plugin.task.api.TaskPluginManager;
import org.apache.dolphinscheduler.registry.api.RegistryClient;
import org.apache.dolphinscheduler.registry.api.RegistryConfiguration;
import org.apache.dolphinscheduler.registry.api.SubscribeListener;
import org.apache.dolphinscheduler.scheduler.api.SchedulerApi;
import org.apache.dolphinscheduler.server.master.cluster.*;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.engine.MasterCoordinator;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEngine;
import org.apache.dolphinscheduler.server.master.engine.system.SystemEventBus;
import org.apache.dolphinscheduler.server.master.engine.system.SystemEventBusFireWorker;
import org.apache.dolphinscheduler.server.master.engine.system.event.GlobalMasterFailoverEvent;
import org.apache.dolphinscheduler.server.master.metrics.MasterServerMetrics;
import org.apache.dolphinscheduler.server.master.registry.MasterRegistryClient;
import org.apache.dolphinscheduler.server.master.rpc.MasterRpcServer;
import org.apache.dolphinscheduler.server.master.utils.MasterThreadFactory;
import org.apache.dolphinscheduler.service.ServiceConfiguration;
import org.apache.dolphinscheduler.service.bean.SpringApplicationContext;

import java.util.Date;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Slf4j
@Import({DaoConfiguration.class,
        ServiceConfiguration.class,
        CommonConfiguration.class,
        StorageConfiguration.class,
        RegistryConfiguration.class})
@SpringBootApplication
public class MasterServer implements IStoppable {

    @Autowired
    private SpringApplicationContext springApplicationContext;

    @Autowired
    private MasterRegistryClient masterRegistryClient;

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private SchedulerApi schedulerApi;

    @Autowired
    private MasterRpcServer masterRPCServer;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private ClusterStateMonitors clusterStateMonitors;

    @Autowired
    private ClusterManager clusterManager;

    @Autowired
    private SystemEventBus systemEventBus;

    @Autowired
    private SystemEventBusFireWorker systemEventBusFireWorker;

    @Autowired
    private MasterCoordinator masterCoordinator;

    public static void main(String[] args) {
        /**
         * 注册未捕获异常监控指标
         * 注册一个 Micrometer Gauge 指标 ds.master.uncached.exception
         * 通过方法引用实时获取未捕获异常计数
         * 用于监控和告警
         */
        MasterServerMetrics.registerUncachedException(DefaultUncaughtExceptionHandler::getUncaughtExceptionCount);

        /**
         * 设置全局未捕获异常处理器
         * 为所有线程设置默认的未捕获异常处理器
         * 当线程抛出未捕获异常时，DefaultUncaughtExceptionHandler 会：
         * 将异常计数加 1（使用 LongAdder）
         * 记录错误日志（包含线程信息和异常堆栈）
         */
        Thread.setDefaultUncaughtExceptionHandler(DefaultUncaughtExceptionHandler.getInstance());
        Thread.currentThread().setName(Constants.THREAD_NAME_MASTER_SERVER);
        SpringApplication.run(MasterServer.class);
    }

    /**
     * run master server
     */
    @PostConstruct
    public void initialized() {
        ServerLifeCycleManager.toRunning();
        final long startupTime = System.currentTimeMillis();

        // init rpc server
        /**
         * 1、扫描注册methodInvoker {@link JdkDynamicServerHandler#registerMethodInvoker(ServerMethodInvoker)}
         * 2、启动 {@link NettyRemotingServer#start()} 服务端 -> spring自动注入初始化配置信息{@link MasterRpcServer#MasterRpcServer(MasterConfig)}
         */
        this.masterRPCServer.start();

        // install task plugin
        TaskPluginManager.loadTaskPlugin();
        DataSourceProcessorProvider.initialize();

        /**
         * 1、业务心跳(守护线程间隔执行,模板方法)
         * 2、注册服务到zookeeper(CuratorFrameworkFactory客户端框架)
         *      MasterRegistryClient -》 RegistryClient -》Registry -》 ZookeeperRegistry
         * 3、将 MasterServer 实例（实现了 IStoppable）注册到 RegistryClient
         *    当注册中心检测到异常（连接断开、故障转移等）时，可回调 MasterServer.stop() 停止服务
         */
        // self tolerant
        this.masterRegistryClient.start();
        this.masterRegistryClient.setRegistryStoppable(this);

        this.masterCoordinator.start();

        /**
         * 启动集群管理器
         * 1、初始化 Master 集群：
         *     - 注册 Master 槽位变化监听器（MasterSlotChangeListener）{@link MasterClusters#registerListener(IClusters.IClustersChangeListener)}
         *     - 从注册中心获取所有 Master 节点并添加到集群 {@link MasterClusters#onServerAdded(MasterServerMetadata)}
         *     - 订阅 Master 节点变化事件，实现动态感知集群变化 {@link RegistryClient#subscribe(String, SubscribeListener)}
         * 2、初始化 Worker 集群：
         *     - 从注册中心获取所有 Worker 节点并添加到集群
         *     - 订阅 Worker 节点变化事件
         *     - 注册 Worker 组变化通知器，监听 Worker 组配置变化
         *
         *
         * 集群流程:
         * master服务启动增加本身到集群{@link MasterClusters#onServerAdded(MasterServerMetadata)} ->
         * 服务启动订阅TreeCache{@link RegistryClient#subscribe(String, SubscribeListener)} ->
         * 适配器模式,TreeCacheListener监听 {@link MasterSlotChangeListenerAdaptor#onMasterSlotChanged(List)} ->
         * 回调通知上层应用 ->
         * 实现整个集群masterServerMap一致  {@link MasterClusters#onServerAdded(MasterServerMetadata)}->
         * 实现mester服务集群互相监控 -〉
         * master基于slot处理各自的Command ->
         * 通过selector分发任务给worker，实现Master集群和Worker集群无中心
         *
         *
         */
        this.clusterManager.start();
        this.clusterStateMonitors.start();

        this.workflowEngine.start();

        this.schedulerApi.start();

        this.systemEventBus.publish(GlobalMasterFailoverEvent.of(new Date(startupTime)));
        this.systemEventBusFireWorker.start();

        MasterServerMetrics.registerMasterCpuUsageGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return systemMetrics.getSystemCpuUsagePercentage();
        });
        MasterServerMetrics.registerMasterMemoryAvailableGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return (systemMetrics.getSystemMemoryMax() - systemMetrics.getSystemMemoryUsed()) / 1024.0 / 1024 / 1024;
        });
        MasterServerMetrics.registerMasterMemoryUsageGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return systemMetrics.getJvmMemoryUsedPercentage();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!ServerLifeCycleManager.isStopped()) {
                close("MasterServer shutdownHook");
            }
        }));
        log.info("MasterServer initialized successfully in {} ms", System.currentTimeMillis() - startupTime);
    }

    @PreDestroy
    public void shutdown() {
        close("MasterServer shutdown");
    }

    public void close(String cause) {
        // set stop signal is true
        // execute only once
        if (!ServerLifeCycleManager.toStopped()) {
            log.warn("MasterServer is already stopped, current cause: {}", cause);
            return;
        }
        // thread sleep 3 seconds for thread quietly stop
        ThreadUtils.sleep(Constants.SERVER_CLOSE_WAIT_TIME.toMillis());
        MasterThreadFactory.getDefaultSchedulerThreadExecutor().shutdownNow();
        try (
                SystemEventBusFireWorker systemEventBusFireWorker1 = systemEventBusFireWorker;
                WorkflowEngine workflowEngine1 = workflowEngine;
                SchedulerApi closedSchedulerApi = schedulerApi;
                MasterRpcServer closedRpcServer = masterRPCServer;
                MasterCoordinator closeMasterCoordinator = masterCoordinator;
                MasterRegistryClient closedMasterRegistryClient = masterRegistryClient;
                // close spring Context and will invoke method with @PreDestroy annotation to destroy beans.
                // like ServerNodeManager,HostManager,TaskResponseService,CuratorZookeeperClient,etc
                SpringApplicationContext closedSpringContext = springApplicationContext) {

            log.info("MasterServer is stopping, current cause : {}", cause);
        } catch (Exception e) {
            log.error("MasterServer stop failed, current cause: {}", cause, e);
            return;
        }
        log.info("MasterServer stopped, current cause: {}", cause);
    }

    @Override
    public void stop(String cause) {
        close(cause);

        // make sure exit after server closed, don't call System.exit in close logic, will cause deadlock if close
        // multiple times at the same time
        System.exit(1);
    }
}
