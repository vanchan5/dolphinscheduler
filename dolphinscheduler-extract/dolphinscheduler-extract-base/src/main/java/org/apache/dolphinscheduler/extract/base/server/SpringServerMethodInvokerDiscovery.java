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

package org.apache.dolphinscheduler.extract.base.server;

import org.apache.dolphinscheduler.extract.base.RpcService;
import org.apache.dolphinscheduler.extract.base.config.NettyServerConfig;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The RpcServer which will auto discovery the {@link ServerMethodInvoker} from Spring container.
 * This class implements {@link BeanPostProcessor} to automatically register RPC service beans
 * when they are initialized by Spring.
 *
 * Note: We also scan existing beans in start() method to ensure all beans initialized before
 * RPC server starts are registered, because @PostConstruct methods may be called before
 * all beans are fully initialized.
 *
 * 实际情况
 * 1、Spring 初始化顺序：
 *      1.1 先初始化所有 BeanPostProcessor（包括 SpringServerMethodInvokerDiscovery）
 *      1.2 再初始化其他 bean（如 WorkflowControlClient）
 *      1.3 每个 bean 初始化完成后，会调用所有 BeanPostProcessor 的 postProcessAfterInitialization
 *      1.4 所有 bean 初始化完成后，才调用所有 bean 的 @PostConstruct（如 MasterServer.initialized()）
 * 2、如果 IWorkflowControlClient 在 masterRPCServer.start() 之前初始化：
 *      2.1 IWorkflowControlClient 初始化完成后，会调用 postProcessAfterInitialization()
 *          方法会被注册到 nettyRemotingServer,此时 nettyRemotingServer 可能还未启动，但不影响注册（注册只是添加到 map）
 * 真正的问题
 * 问题是：IWorkflowControlClient 在 masterRPCServer.start() 之后才初始化。
 * 1、 @PostConstruct 方法（MasterServer.initialized()）在所有 bean 初始化完成后才执行
 * 2、但 Spring 的 bean 初始化顺序不确定，可能存在依赖关系导致某些 bean 延迟初始化
 * 3、如果 IWorkflowControlClient 因为依赖关系在 @PostConstruct 之后才初始化，那么：
 *      3.1、masterRPCServer.start() 被调用时，WorkflowControlClient 还未初始化
 *      3.2、postProcessAfterInitialization 还未被调用
 *      3.3、方法还未注册
 * 解决方案的作用
 * scanAndRegisterExistingRpcServices() 的作用是：
 * 在 start() 时扫描所有已初始化的 bean
 * 确保在 RPC 服务器启动前，所有已初始化的 RPC 服务 bean 都被注册
 * 处理 bean 初始化顺序不确定的情况
 */
@Slf4j
public class SpringServerMethodInvokerDiscovery extends RpcServer implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;
    
    /**
     * Track registered RPC service beans to avoid duplicate registration.
     * Key is the bean instance (identity), value is the bean name.
     */
    private final Set<Object> registeredBeans = ConcurrentHashMap.newKeySet();

    public SpringServerMethodInvokerDiscovery(NettyServerConfig nettyServerConfig) {
        super(nettyServerConfig);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 调用master服务可能会出现Cannot find the ServerMethodInvoker of public abstract org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowManualTriggerResponse org.apache.dolphinscheduler.extract.master.IWorkflowControlClient.manualTriggerWorkflow(org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowManualTriggerRequest
     * 原因是
     * MasterServer.initialized() 在 @PostConstruct 中调用 masterRPCServer.start() 时，
     * Spring 容器可能尚未完成所有 bean 的初始化。虽然 BeanPostProcessor 会优先初始化，
     * 但 postProcessAfterInitialization 只会在 bean 初始化后调用；
     * 如果 WorkflowControlClient 在 start() 之后才初始化，启动时它还未注册。
     * 所以需要改为在 start() 时扫描 scanAndRegisterExistingRpcServices();，确保启动前已注册所有已初始化的 bean：
     *
     */
    @Override
    public void start() {
        // Before starting the RPC server, scan and register all existing RPC service beans
        // that have been initialized so far. This is necessary because @PostConstruct methods
        // (like MasterServer.initialized()) may be called before all beans are fully initialized,
        // and we need to ensure all RPC services are registered before the server starts accepting requests.
        if (applicationContext != null) {
            scanAndRegisterExistingRpcServices();
        }
        super.start();
    }

    /**
     * Scan and register all existing RPC service beans in the application context.
     * This ensures that beans initialized before the RPC server starts are also registered.
     *
     * 问题原因
     * 1. Spring 初始化顺序：
     *      1.1、BeanPostProcessor 会优先初始化
     *      1.2、但 @PostConstruct 方法（如 MasterServer.initialized()）可能在所有 bean 初始化完成前被调用
     *      1.3、当 masterRPCServer.start() 被调用时，WorkflowControlClient 可能尚未初始化
     * 2. 为什么需要手动扫描：
     *      BeanPostProcessor 的 postProcessAfterInitialization 只会在 bean 初始化后调用
     *      如果 bean 在 start() 之后才初始化，启动时它还未注册
     *      手动扫描确保在启动前注册所有已初始化的 bean
     *
     * 代码同时使用两种机制：
     * 1. 在 start() 时扫描已存在的 bean：处理在 RPC 服务器启动前已初始化的 bean（如 WorkflowControlClient）
     * 2. 在 postProcessAfterInitialization() 中注册：处理在 RPC 服务器启动后才初始化的 bean
     * 这样可确保所有 RPC 服务 bean 都被正确注册，无论初始化顺序如何。
     */
    private void scanAndRegisterExistingRpcServices() {
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        int registeredCount = 0;
        for (Map.Entry<String, Object> entry : allBeans.entrySet()) {
            Object bean = entry.getValue();
            // Skip this bean itself to avoid infinite loop
            if (bean == this) {
                continue;
            }
            // Check if the bean is an RPC service bean and not already registered
            // This avoids duplicate registration and unnecessary calls
            if (isRpcServiceBean(bean) && !registeredBeans.contains(bean)) {
                registerServerMethodInvokerProvider(bean);
                registeredBeans.add(bean);
                registeredCount++;
                log.debug("Registered existing RPC service bean during startup scan: {}", entry.getKey());
            }
        }
        if (registeredCount > 0) {
            log.info("Registered {} existing RPC service bean(s) before starting RPC server", registeredCount);
        }
    }
    
    /**
     * Check if a bean implements an interface annotated with @RpcService.
     * This helper method extracts the common logic shared with registerServerMethodInvokerProvider()
     * to avoid code duplication.
     */
    private boolean isRpcServiceBean(Object bean) {
        for (Class<?> anInterface : bean.getClass().getInterfaces()) {
            if (anInterface.getAnnotation(RpcService.class) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // Automatically register RPC service beans when they are initialized
        // This handles beans that are initialized after the RPC server starts
        // Check if already registered to avoid duplicate registration (e.g., if scanAndRegisterExistingRpcServices
        // was called before this bean was initialized, or if this method is called multiple times)
        if (isRpcServiceBean(bean) && !registeredBeans.contains(bean)) {
            registerServerMethodInvokerProvider(bean);
            registeredBeans.add(bean);
            log.debug("Registered RPC service bean via BeanPostProcessor: {}", beanName);
        }
        return bean;
    }

}
