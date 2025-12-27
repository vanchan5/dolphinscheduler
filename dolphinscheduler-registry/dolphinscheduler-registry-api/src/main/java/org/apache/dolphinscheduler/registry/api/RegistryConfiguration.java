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

package org.apache.dolphinscheduler.registry.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 配置驱动: registry.type=zookeeper
 *
 * {@link RegistryConfiguration#registryClient(Registry)} 根据Registry bean注册registryClient,初始化registryClient
 *
 */
@Configuration
public class RegistryConfiguration {

    /**
     * 参数必须是一个bean,registry的Bean的创建,留给其子类来实现,
     * RegistryConfiguration.registryClient() 方法被调用时，Spring 会自动从容器中查找 Registry 类型的 Bean 并注入
     * registry对外提供接口,支持多种 Registry 实现
     * @param registry
     * @return
     */
    @Bean
    @ConditionalOnMissingBean
    public RegistryClient registryClient(Registry registry) {
        return new RegistryClient(registry);
    }

}
