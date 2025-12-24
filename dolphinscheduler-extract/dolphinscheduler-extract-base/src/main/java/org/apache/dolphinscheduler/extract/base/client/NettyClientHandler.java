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

import org.apache.dolphinscheduler.extract.base.StandardRpcResponse;
import org.apache.dolphinscheduler.extract.base.future.ResponseFuture;
import org.apache.dolphinscheduler.extract.base.protocal.HeartBeatTransporter;
import org.apache.dolphinscheduler.extract.base.protocal.Transporter;
import org.apache.dolphinscheduler.extract.base.serialize.JsonSerializer;
import org.apache.dolphinscheduler.extract.base.utils.ChannelUtils;

import lombok.extern.slf4j.Slf4j;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 1、@ChannelHandler.Sharable,所有channel共享这个NettyClientHandler
 * 2、handler是在EventLoop的线程处理的
 *      Netty 的 EventLoop 线程由 EventLoopGroup 管理
 *      2.1 负责处理网络 I/O 事件（接收数据、发送数据等）
 *      2.2 线程名类似：NettyClientThread-
 * 具体分析  -- 》 netty-channel-通信.md
 *
 */
@ChannelHandler.Sharable
@Slf4j
public class NettyClientHandler extends ChannelInboundHandlerAdapter {

    private final NettyRemotingClient nettyRemotingClient;

    public NettyClientHandler(NettyRemotingClient nettyRemotingClient) {
        this.nettyRemotingClient = nettyRemotingClient;
    }

    /**
     * 当服务端连接断开调用
     * 1、服务端主动关闭连接
     * 2、网络中断
     * 3、读取到 EOF
     * 4、写操作失败
     * 5、心跳超时(服务端检测) {@link NettyRemotingClient#start()}
     * org.apache.dolphinscheduler.extract.base.server.JdkDynamicServerHandler#userEventTriggered(io.netty.channel.ChannelHandlerContext, java.lang.Object)
     *
     * 分析 --》 服务端断开连接.md
     * @param ctx
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Channel inactive: {}", ctx.channel());
        nettyRemotingClient.onChannelInactive(ChannelUtils.toAddress(ctx.channel()));
        ctx.channel().close();
    }

    /**
     * 服务端处理结果 {org.apache.dolphinscheduler.extract.base.server.JdkDynamicServerHandler#channelRead(ChannelHandlerContext, Object)}
     * channel.writeAndFlush(transporter)之后出发调用这个接口
     * @param ctx
     * @param msg
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        processReceived((Transporter) msg);
    }

    private void processReceived(final Transporter transporter) {
        ResponseFuture future = ResponseFuture.getFuture(transporter.getHeader().getOpaque());
        if (future == null) {
            log.warn("Cannot find the ResponseFuture if transporter: {}", transporter);
            return;
        }
        // 二进制解码
        StandardRpcResponse deserialize = JsonSerializer.deserialize(transporter.getBody(), StandardRpcResponse.class);
        future.setIRpcResponse(deserialize);
        future.putResponse(deserialize);
    }

    /**
     * 1. 触发场景：
     *  1.1 读取数据时异常
     *  1.2 写入数据时异常
     *  1.3 连接异常
     * 2. 处理方式：
     *  2.1 记录异常日志
     *  2.2 清理连接池
     *  2.3 关闭 Channel
     * @param ctx
     * @param cause
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("NettyClientHandler catch an exception on channel: {}", ctx.channel(), cause);
        nettyRemotingClient.onChannelInactive(ChannelUtils.toAddress(ctx.channel()));
        ctx.channel().close();
    }

    /**
     * 客户端 10 秒未发送数据 -》触发写空闲事件 -》 客户端发送心跳包 -》保持连接活跃
     *
     * @param ctx
     * @param evt
     * @throws Exception
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            // 发送心跳数据,服务端handler对心跳请求处理,如:打印日志
            // org.apache.dolphinscheduler.extract.base.server.JdkDynamicServerHandler.processReceived
            ctx.channel()
                    .writeAndFlush(HeartBeatTransporter.getHeartBeatTransporter())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (log.isDebugEnabled()) {
                log.info("Client send heartbeat to: {}", ctx.channel().remoteAddress());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

}
