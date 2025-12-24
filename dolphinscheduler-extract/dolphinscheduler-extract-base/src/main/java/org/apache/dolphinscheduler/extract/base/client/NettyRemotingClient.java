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

package org.apache.dolphinscheduler.extract.base.client;

import io.netty.channel.*;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.extract.base.IRpcResponse;
import org.apache.dolphinscheduler.extract.base.RpcMethodRetryStrategy;
import org.apache.dolphinscheduler.extract.base.SyncRequestDto;
import org.apache.dolphinscheduler.extract.base.config.NettyClientConfig;
import org.apache.dolphinscheduler.extract.base.exception.RemoteException;
import org.apache.dolphinscheduler.extract.base.exception.RemoteTimeoutException;
import org.apache.dolphinscheduler.extract.base.future.ResponseFuture;
import org.apache.dolphinscheduler.extract.base.metrics.ClientSyncDurationMetrics;
import org.apache.dolphinscheduler.extract.base.metrics.ClientSyncExceptionMetrics;
import org.apache.dolphinscheduler.extract.base.metrics.RpcMetrics;
import org.apache.dolphinscheduler.extract.base.protocal.Transporter;
import org.apache.dolphinscheduler.extract.base.protocal.TransporterDecoder;
import org.apache.dolphinscheduler.extract.base.protocal.TransporterEncoder;
import org.apache.dolphinscheduler.extract.base.utils.Host;
import org.apache.dolphinscheduler.extract.base.utils.NettyUtils;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

@Slf4j
public class NettyRemotingClient implements AutoCloseable {

    private final Bootstrap bootstrap = new Bootstrap();

    private final ReentrantLock channelsLock = new ReentrantLock();
    private final Map<Host, Channel> channels = new ConcurrentHashMap<>();

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private final EventLoopGroup workerGroup;

    private final NettyClientConfig clientConfig;

    private final NettyClientHandler clientHandler;

    public NettyRemotingClient(final NettyClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        ThreadFactory nettyClientThreadFactory = ThreadUtils.newDaemonThreadFactory("NettyClientThread-");
        if (Epoll.isAvailable()) {
            this.workerGroup = new EpollEventLoopGroup(clientConfig.getWorkerThreads(), nettyClientThreadFactory);
        } else {
            this.workerGroup = new NioEventLoopGroup(clientConfig.getWorkerThreads(), nettyClientThreadFactory);
        }
        this.clientHandler = new NettyClientHandler(this);

        this.start();
    }

    private void start() {

        this.bootstrap
                /*
                 * 每个 Channel 绑定到一个 EventLoop 线程
                 * 一个 EventLoop 可以处理多个 Channel
                 * 每个 EventLoop 串行处理其绑定的 Channel 的所有 I/O 事件
                 *
                 * 主线程（业务线程）              EventLoop 线程（Netty 内部线程）
                         |                                |
                         |-- writeAndFlush() ----------->|
                         |                                |-- 1. 将数据放入 Channel 的 outbound 缓冲区
                         |-- 立即返回 ChannelFuture ------|-- 2. 注册写入任务到 EventLoop 的任务队列
                         |-- 继续执行其他代码              |-- 3. EventLoop 线程轮询时处理写入任务
                         |                                |-- 4. 实际执行网络写入操作
                         |                                |-- 5. 写入完成后触发 addListener 回调
                         |<-- addListener 回调触发 -------|
                 */
                .group(this.workerGroup)
                .channel(NettyUtils.getSocketChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, clientConfig.isSoKeepalive())
                .option(ChannelOption.TCP_NODELAY, clientConfig.isTcpNoDelay())
                .option(ChannelOption.SO_SNDBUF, clientConfig.getSendBufferSize())
                .option(ChannelOption.SO_RCVBUF, clientConfig.getReceiveBufferSize())
                //建立 TCP 连接的超时时间
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientConfig.getConnectTimeoutMillis())
                .handler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    public void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("client-idle-handler",
                                        // 空闲状态事件处理
                                        new IdleStateHandler(
                                                0,
                                                clientConfig.getHeartBeatIntervalMillis(),
                                                0,
                                                TimeUnit.MILLISECONDS))//设置客户端:每 10 秒发送心跳
                                .addLast(new TransporterDecoder(), clientHandler, new TransporterEncoder());
                    }
                });
        isStarted.compareAndSet(false, true);
    }

    public IRpcResponse sendSync(final SyncRequestDto syncRequestDto) throws RemoteException {
        final Host host = syncRequestDto.getServerHost();
        final Transporter transporter = syncRequestDto.getTransporter();
        final long timeoutMillis = syncRequestDto.getTimeoutMillis() < 0 ? clientConfig.getDefaultRpcTimeoutMillis()
                : syncRequestDto.getTimeoutMillis();

        final RpcMethodRetryStrategy retryStrategy = syncRequestDto.getRetryStrategy();

        int maxRetryTimes = retryStrategy.maxRetryTimes();
        int currentExecuteTimes = 1;

        // 重试机制,成功时立即返回,支持重试间隔（
        while (true) {
            final long start = System.currentTimeMillis();
            try {
                return doSendSync(transporter, host, timeoutMillis);
            } catch (Exception ex) {
                ClientSyncExceptionMetrics clientSyncExceptionMetrics =
                        ClientSyncExceptionMetrics.of(syncRequestDto, ex);
                RpcMetrics.recordClientSyncRequestException(clientSyncExceptionMetrics);

                if (currentExecuteTimes < maxRetryTimes
                        //判断当前异常是否属于需要重试的异常类型
                        && Arrays.stream(retryStrategy.retryFor()).anyMatch(e -> e.isInstance(ex))) {
                    // 满足两个条件才重试：
                    // 1. 重试次数未达到上限
                    // 2. 当前异常类型匹配重试策略中配置的异常类型
                    currentExecuteTimes++;
                    if (retryStrategy.retryInterval() > 0) {
                        ThreadUtils.sleep(retryStrategy.retryInterval());
                    }
                    continue;
                }

                if (ex instanceof RemoteException) {
                    throw (RemoteException) ex;
                } else {
                    throw new RemoteException("Call method to " + host + " failed", ex);
                }
            } finally {
                ClientSyncDurationMetrics clientSyncDurationMetrics = ClientSyncDurationMetrics
                        .of(syncRequestDto)
                        .withMilliseconds(System.currentTimeMillis() - start);
                RpcMetrics.recordClientSyncRequestDuration(clientSyncDurationMetrics);
            }
        }
    }

    /**
     *
     * 正常情况下: 调用writeAndFlush()立即往下执行responseFuture.waitResponse();
     * 主线程                           EventLoop线程                     服务端
     *   |                                |                                |
     *   |-- writeAndFlush() ----------->|                                |
     *   |                                |-- 发送数据 ------------------->|
     *   |-- waitResponse() 阻塞等待      |                                |
     *   |                                |                                |
     *   |                                |<-- 发送完成回调                |
     *   |                                |                                |
     *   |                                |  成功: setSendOk(true), return |
     *   |                                |  失败: putResponse(null) ----->| 唤醒waitResponse()
     *   |                                |                                |
     *   |                                |<-- 服务端响应 -----------------|
     *   |                                |-- channelRead()                |
     *   |                                |-- processReceived()            |
     *   |                                |-- putResponse(response) ------>| 唤醒waitResponse()
     *   |<-- waitResponse() 返回 --------|                                |
     *   |-- 继续执行                      |
     *
     *  时间线：
     * T1: 主线程调用 writeAndFlush() → 数据入队，立即返回 ChannelFuture
     * T2: 主线程继续执行（比如执行 waitResponse() 等待响应）
     * T3: EventLoop 线程从队列取出任务
     * T4: EventLoop 线程执行实际的网络写入
     * T5: 写入完成，触发 addListener 回调（在 EventLoop 线程中执行）
     * T6: 服务端响应到达
     * T7: EventLoop 线程触发 channelRead() 回调
     *
     * 极端情况下: 调用writeAndFlush()没有立即往下执行responseFuture.waitResponse();不会出问题
     *主线程（业务线程）                    EventLoop 线程
     *      |                                    |
     *      |-- writeAndFlush().addListener() -->| 注册回调
     *      |<-- 立即返回 -----------------------|
     *      |                                    |
     *      |-- 继续执行（可能在等待 CPU）       |
     *      |                                    |-- 写入操作很快完成
     *      |                                    |-- 触发 addListener 回调
     *      |                                    |-- 执行 244-247 行或 249-254 行
     *      |                                    |-- 回调执行完毕 ✅
     *      |                                    |
     *      |-- waitResponse() 开始执行 ---------| （此时回调可能已经执行完）
     *      |-- latch.await() 等待 -------------|
     *为什么不会出问题？
     * 即使出现该时序，代码依然安全，原因：
     * 1. 写入成功的情况
     *      只设置 sendOk = true，不调用 putResponse()
     *      CountDownLatch 状态不变（仍然为 1）
     *      waitResponse() 后续正常等待，不会被提前唤醒
     *      writeAndFlush() 设计为异步，是因为：
     *2. 写入失败的情况
     *      即使 waitResponse() 还未执行到 latch.await()，CountDownLatch 的状态已经变为 0
     *      当 waitResponse() 执行到 latch.await() 时，由于计数已经是 0，会立即返回，不会阻塞
     *3. CountDownLatch 的特性
     *      CountDownLatch 的特性保证了线程安全：
     *      如果 countDown() 在 await() 之前执行：await() 会立即返回（不会阻塞）
     *      如果 await() 在 countDown() 之前执行：await() 会阻塞等待 countDown()
     *时间戳说明极端情况:
     * T1: 主线程执行 writeAndFlush().addListener() - 注册回调，立即返回
     * T2: 主线程继续执行（可能被其他线程抢占 CPU）
     * T3: EventLoop 线程执行写入操作
     * T4: EventLoop 线程写入完成，触发 addListener 回调
     * T5: EventLoop 线程执行回调（244-247 或 249-254 行）
     *     如果是成功：只设置 sendOk = true，不唤醒
     *     如果是失败：调用 putResponse(null)，唤醒（但如果 waitResponse 还没执行，latch 已经 countDown）
     * T6: 主线程获得 CPU，执行到 waitResponse()
     * T7: waitResponse() 执行 latch.await()
     *     如果之前已经 countDown：立即返回（失败情况）
     *     如果之前没有 countDown：正常等待（成功情况）
     *
     * CountDownLatch 的线程安全特性保证了这个设计的正确性。
     * 这是线程安全的并发编程模式，利用了 CountDownLatch 的特性，避免了显式同步锁的开销。
     *
     *设计理念: 基于 NIO 的非阻塞模型
     *      EventLoop 线程模型需要异步任务队列
     *      避免阻塞调用线程，提升性能
     *      符合事件驱动的设计模式
     *
     * @param transporter
     * @param serverHost
     * @param timeoutMills
     * @return
     * @throws RemoteException
     * @throws InterruptedException
     *
     * 该设计将发送状态与响应结果解耦，仅在发送失败时提前唤醒，成功时等待完整响应。
     */
    private IRpcResponse doSendSync(final Transporter transporter,
                                    final Host serverHost,
                                    long timeoutMills) throws RemoteException, InterruptedException {
        final Channel channel = getOrCreateChannel(serverHost);
        if (channel == null) {
            throw new RemoteException(String.format("connect to : %s fail", serverHost));
        }
        final ResponseFuture responseFuture = new ResponseFuture(transporter.getHeader().getOpaque(), timeoutMills);
        /*
         * 这个时候channel已连接好
         * channel.writeAndFlush(transporter)异步发送,不会阻塞，立即返回,返回的是 writeAndFlush 的 ChannelFuture,用于等待数据发送完成
         * ⭐⭐数据入队，等待 Netty 的 EventLoop 线程处理⭐⭐
         * channel.writeAndFlush(transporter)是在EventLoopGroup的eventLoop的线程串行执行
         *      ⭐a. 每个 Channel 只绑定到一个 EventLoop⭐
         *      ⭐b. 一个 EventLoop 可以处理多个 Channel⭐
         */
        channel.writeAndFlush(transporter).addListener(future -> {
            /*
             * addListener 只是注册一个回调函数
             * ✅不会阻塞当前线程,不会调用 wait(),不会进入 WaitSet
             *
             * ⭐触发点：在 Netty 的 EventLoop 线程中异步执行（数据写入完成时）
             * 说明：回调的触发时机取决于写入结果：
             * 成功：仅设置 responseFuture.setSendOk(true) 并 return，⭐不唤醒等待⭐
             * 失败：设置状态、cause，并调用 responseFuture.putResponse(null) ⭐立即唤醒等待⭐
             */
            if (future.isSuccess()) {
                // 不唤醒等待
                responseFuture.setSendOk(true);
                return;
            } else {
                responseFuture.setSendOk(false);
            }
            responseFuture.setCause(future.cause());
            // 立即唤醒等待
            responseFuture.putResponse(null);
            log.error("Send Sync request {} to host {} failed", transporter, serverHost, responseFuture.getCause());
            });
        /**
         * sync wait for result
         * 异步发送,同步等待
         * 在channel.writeAndFlush(transporter).addListener之后立即执行
         * 等待服务端{@link JdkDynamicServerHandler#processReceived(Channel, Transporter)}的channel.writeAndFlush(response);
         * 处理完通信给客户端{@link NettyClientHandler#processReceived(Transporter)}的future.putResponse(deserialize);
         * 唤醒
         */
        final IRpcResponse iRpcResponse = responseFuture.waitResponse();
        if (iRpcResponse != null) {
            return iRpcResponse;
        }
        if (responseFuture.isSendOK()) {
            throw new RemoteTimeoutException(serverHost.toString(), timeoutMills, responseFuture.getCause());
        } else {
            throw new RemoteException(serverHost.toString(), responseFuture.getCause());
        }
    }

    /**
     * 线程A                         线程B                         线程C
     *   |                             |                             |
     *   |-- 第一次检查（无锁）         |-- 第一次检查（无锁）         |-- 第一次检查（无锁）
     *   |-- channels.get(host)        |-- channels.get(host)        |-- channels.get(host)
     *   |-- 返回 null                 |-- 返回 null                 |-- 返回 null
     *   |                             |                             |
     *   |-- 尝试获取锁                |-- 尝试获取锁（等待）         |-- 尝试获取锁（等待）
     *   |-- channelsLock.lock() ✅    |-- channelsLock.lock() ⏳    |-- channelsLock.lock() ⏳
     *   |                             |                             |
     *   |-- 第二次检查（有锁）         |                             |
     *   |-- channels.get(host)        |                             |
     *   |-- 仍然是 null               |                             |
     *   |                             |                             |
     *   |-- createChannel(host) 🆕    |                             |
     *   |-- channels.put(host, ch)    |                             |
     *   |-- 释放锁                    |                             |
     *   |-- channelsLock.unlock()     |                             |
     *   |                             |                             |
     *   |                             |-- 获得锁 ✅                  |
     *   |                             |-- 第二次检查（有锁）          |
     *   |                             |-- channels.get(host) ✅      |
     *   |                             |-- 返回已存在的 channel       |
     *   |                             |-- 释放锁                     |
     *   |                             |                             |
     *   |                             |                             |-- 获得锁 ✅
     *   |                             |                             |-- 第二次检查（有锁）
     *   |                             |                             |-- channels.get(host) ✅
     *   |                             |                             |-- 返回已存在的 channel
     *
     *  关键点
     * 1) 为什么需要两次检查
     *      第一次检查（无锁，第 322-324 行）：快速路径，大多数情况下 Channel 已存在，直接返回，避免锁开销
     *      第二次检查（有锁，第 328-330 行）：避免在获取锁期间，其他线程已创建并放入 Channel，防止重复创建
     * 2) 为什么使用 ConcurrentHashMap
     *      channels.get() 是线程安全的，可以在无锁环境下进行第一次检查
     *      但写入操作（channels.put()）仍需要同步，确保原子性
     * @param host
     * @return
     */
    Channel getOrCreateChannel(Host host) {
        Channel channel = channels.get(host);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        try {
            channelsLock.lock();
            channel = channels.get(host);
            if (channel != null && channel.isActive()) {
                return channel;
            }
            // 连接异步转同步,得到的是已经连接的channel
            channel = createChannel(host);
            channels.put(host, channel);
        } finally {
            channelsLock.unlock();
        }
        return channel;
    }

    /**
     * create channel
     *
     * @param host host
     * @return channel
     */
    Channel createChannel(Host host) {
        try {
            // 异步,立即返回ChannelFuture
            ChannelFuture future = bootstrap.connect(new InetSocketAddress(host.getIp(), host.getPort()));
            /** 是 Netty 中常见的同步等待异步操作完成的模式
             * 转同步,等待结果返回,成功会调用{@link DefaultChannelPromise#setSuccess()}
             */
            future = future.sync();
            if (future.isSuccess()) {
                return future.channel();
            } else {
                throw new IllegalArgumentException("connect to host: " + host + " failed", future.cause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Connect to host: " + host + " failed", e);
        }
    }

    @Override
    public void close() {
        if (isStarted.compareAndSet(true, false)) {
            try {
                closeChannels();
                if (workerGroup != null) {
                    // 等待处理再关闭
                    this.workerGroup.shutdownGracefully();
                }
                log.info("netty client closed");
            } catch (Exception ex) {
                log.error("netty client close exception", ex);
            }
        }
    }

    private void closeChannels() {
        try {
            channelsLock.lock();
            channels.values().forEach(Channel::close);
            channels.clear();
        } finally {
            channelsLock.unlock();
        }
    }

    /**
     * 与服务端断开连接 {@link NettyClientHandler#channelInactive(ChannelHandlerContext)}
     * 触发调用close方法
     *
     *
     * 时间线：
     * T1: 线程A 执行 getOrCreateChannel()
     *     - 第一次检查：channel = null
     *     - 获取锁
     *     - 第二次检查：channel = null
     *     - 创建新 channel（createChannel 需要时间）
     *
     * T2: 线程B（EventLoop线程）检测到 channel 断开
     *     - 调用 onChannelInactive()
     *     - 直接执行 channels.remove(host) ✅ ConcurrentHashMap 是线程安全的
     *
     * T3: 线程A 继续执行
     *     - channels.put(host, channel)
     *     - 释放锁
     * 不过，由于使用的是 ConcurrentHashMap，remove 操作本身是线程安全的，主要问题在于可能放回一个已失效的 channel。
     * ⭐但是即使放回了 inactive 的 channel，后续调用也会因为 channel.isActive() 检查而重新创建⭐
     *
     * 结果：可能将一个 inactive 的 channel 重新放入 map
     * @param host
     */
    public void onChannelInactive(final Host host) {
        channels.remove(host);
    }
}
