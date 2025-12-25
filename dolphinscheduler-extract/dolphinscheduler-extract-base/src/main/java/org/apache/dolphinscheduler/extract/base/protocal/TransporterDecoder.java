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

package org.apache.dolphinscheduler.extract.base.protocal;

import org.apache.dolphinscheduler.extract.base.serialize.JsonSerializer;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

/**
 *
 * 使用 ReplayingDecoder 实现状态机解码
 * 将网络接收的二进制数据解码为 Transporter 对象。
 * ByteBuf:
 * ┌──────┬─────────┬──────────────┬──────────────────┬──────────────┬──────────────────┐
 * │ MAGIC│ VERSION │ HEADER_LEN   │ HEADER_BYTES      │ BODY_LEN     │ BODY_BYTES       │
 * │(1字节)│(1字节)  │  (4字节)     │ (变长，JSON)      │  (4字节)     │ (变长，JSON)     │
 * └──────┴─────────┴──────────────┴──────────────────┴──────────────┴──────────────────┘
 *          ↑                              ↑                              ↑
 *          │                              │                              │
 *    协议标识符                     Header 序列化结果              Body 序列化结果
 *
 * MAGIC → VERSION → HEADER_LENGTH → HEADER → BODY_LENGTH → BODY → MAGIC (循环)
 *
 * 每个状态读取固定字段
 * checkpoint() 保存状态，数据不足时自动回退
 * ReplayingDecoder 自动处理数据不足的情况
 *
 * 自动处理数据不足：数据未到齐时自动等待，无需手动检查
 * 简化代码：无需手动检查 readableBytes()
 * 状态管理：通过 checkpoint() 管理解码状态
 */
@Slf4j
public class TransporterDecoder extends ReplayingDecoder<TransporterDecoder.State> {

    public TransporterDecoder() {
        super(State.MAGIC);
    }

    private int headerLength;
    private byte[] header;
    private int bodyLength;
    private byte[] body;

    /**
     * 枚举值本身没有数值意义,只是标识符,顺序才是关键
     *
     * 顺序一致：State 枚举顺序 = 协议字段顺序
     * 操作匹配：每个 State 的读取操作与编码器的写入操作匹配
     * 状态机：通过 checkpoint() 控制状态流转
     *
     * 完整对应关系图
     *      编码器写入顺序                   解码器状态顺序
     *      ─────────────────              ─────────────────
     *      writeByte(MAGIC)        ←→     State.MAGIC
     *      writeByte(VERSION)      ←→     State.VERSION
     *      writeInt(headerLen)     ←→     State.HEADER_LENGTH
     *      writeBytes(header)      ←→     State.HEADER
     *      writeInt(bodyLen)       ←→     State.BODY_LENGTH
     *      writeBytes(body)        ←→     State.BODY
     *
     * 这种设计保证了协议解析的正确性和可靠性，即使数据分片到达也能正确处理。
     *
     * @param ctx
     * @param in
     * @param out
     * @throws Exception
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (state()) {
            case MAGIC:
                checkMagic(in.readByte());
                checkpoint(State.VERSION);
            case VERSION:
                checkVersion(in.readByte());
                checkpoint(State.HEADER_LENGTH);
            case HEADER_LENGTH:
                headerLength = in.readInt();
                checkpoint(State.HEADER);
            case HEADER:
                header = new byte[headerLength];
                in.readBytes(header);
                checkpoint(State.BODY_LENGTH);
            case BODY_LENGTH:
                bodyLength = in.readInt();
                checkpoint(State.BODY);
            case BODY:
                body = new byte[bodyLength];
                in.readBytes(body);
                Transporter transporter =
                        Transporter.of(JsonSerializer.deserialize(header, TransporterHeader.class), body);
                out.add(transporter);
                checkpoint(State.MAGIC);
                break;
            default:
                log.warn("unknown decoder state {}", state());
        }
    }

    private void checkMagic(byte magic) {
        if (magic != Transporter.MAGIC) {
            throw new IllegalArgumentException("illegal packet [magic]" + magic);
        }
    }

    private void checkVersion(byte version) {
        if (version != Transporter.VERSION) {
            throw new IllegalArgumentException("illegal protocol [version]" + version);
        }
    }

    enum State {
        MAGIC,
        VERSION,
        HEADER_LENGTH,
        HEADER,
        BODY_LENGTH,
        BODY;
    }

}
