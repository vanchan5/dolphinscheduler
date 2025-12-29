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

package org.apache.dolphinscheduler.eventbus;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import lombok.Builder;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * The abstract class of delay event, the event will be triggered after the delay time.
 * <p> You can extend this class to implement your own delay event.
 */
@ToString
@SuperBuilder
public abstract class AbstractDelayEvent implements IEvent, Delayed {

    private static final long DEFAULT_DELAY_TIME = 0;

    protected long delayTime;

    @Builder.Default
    protected long createTimeInNano = System.nanoTime();

    // set create time as default if the inheritor didn't call super()
    @Builder.Default
    protected long expiredTimeInNano = System.nanoTime();

    public AbstractDelayEvent() {
        this(DEFAULT_DELAY_TIME);
    }

    public AbstractDelayEvent(final long delayTime) {
        this(delayTime, System.nanoTime());
    }

    /**
     * 构造函数：创建延迟事件对象
     *
     * @param delayTime 延迟时间（单位：毫秒）
     * @param createTimeInNano 事件创建时间（单位：纳秒）
     */
    public AbstractDelayEvent(final long delayTime, final long createTimeInNano) {
        this.delayTime = delayTime;
        this.createTimeInNano = createTimeInNano;
        // 计算过期时间：创建时间 + 延迟时间（转换为纳秒）
        // delayTime单位为毫秒，需要乘以1_000_000转换为纳秒
        this.expiredTimeInNano = this.delayTime * 1_000_000 + this.createTimeInNano;
    }

    /**
     * 实现Delayed接口的方法，计算剩余延迟时间
     * DelayQueue会调用此方法来判断事件是否到期
     *
     * @param unit 时间单位
     * @return 剩余延迟时间（转换为指定单位）
     *         - 返回值 > 0：事件未到期，还需要等待
     *         - 返回值 <= 0：事件已到期，可以取出处理
     */
    @Override
    public long getDelay(TimeUnit unit) {
        // 计算剩余延迟时间 = 过期时间 - 当前时间
        // expiredTimeInNano = createTimeInNano + delayTime * 1_000_000
        // delay = (createTimeInNano + delayTime * 1_000_000) - System.nanoTime()
        long delay = createTimeInNano + delayTime * 1_000_000 - System.nanoTime();
        // 将纳秒转换为指定的时间单位
        return unit.convert(delay, TimeUnit.NANOSECONDS);
    }

    /**
     * 实现Comparable接口的方法，用于DelayQueue内部排序
     * DelayQueue使用PriorityQueue按过期时间升序排序，确保最早到期的事件在队列头部
     *
     * <p>排序规则（升序）：
     * <ul>
     *   <li>过期时间早的事件排在前面（优先级高）</li>
     *   <li>过期时间晚的事件排在后面（优先级低）</li>
     * </ul>
     *
     * <p>举例说明：
     * <pre>
     * 假设当前时间：1000纳秒
     * 
     * 事件A：过期时间 = 2000纳秒（延迟1毫秒）
     * 事件B：过期时间 = 5000纳秒（延迟4毫秒）
     * 事件C：过期时间 = 3000纳秒（延迟2毫秒）
     * 
     * compareTo比较结果：
     * - A.compareTo(B) = Long.compare(2000, 5000) = 负数 → A排在B前面
     * - A.compareTo(C) = Long.compare(2000, 3000) = 负数 → A排在C前面
     * - C.compareTo(B) = Long.compare(3000, 5000) = 负数 → C排在B前面
     * 
     * 最终队列顺序（从头部到尾部）：A → C → B
     * take()方法会优先取出事件A（最早到期）
     * </pre>
     *
     * @param other 另一个延迟事件对象
     * @return 比较结果
     *         - 负数：当前事件过期时间更早，排在前面（优先级更高）
     *         - 0：两个事件过期时间相同
     *         - 正数：当前事件过期时间更晚，排在后面（优先级更低）
     */
    @Override
    public int compareTo(Delayed other) {
        /**
         * 升序排列：按过期时间从小到大排序,过期时间越小，优先级越高
         */
        return Long.compare(this.expiredTimeInNano, ((AbstractDelayEvent) other).expiredTimeInNano);
    }

}
