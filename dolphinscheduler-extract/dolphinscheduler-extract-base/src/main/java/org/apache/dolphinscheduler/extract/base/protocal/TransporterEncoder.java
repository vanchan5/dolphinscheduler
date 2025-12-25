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

import org.apache.dolphinscheduler.extract.base.exception.RemoteException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 编码格式
 *
 * ByteBuf:
 * ┌──────┬─────────┬──────────────┬──────────────────┬──────────────┬──────────────────┐
 * │ MAGIC│ VERSION │ HEADER_LEN   │ HEADER_BYTES      │ BODY_LEN     │ BODY_BYTES       │
 * │(1字节)│(1字节)  │  (4字节)     │ (变长，JSON)      │  (4字节)     │ (变长，JSON)     │
 * └──────┴─────────┴──────────────┴──────────────────┴──────────────┴──────────────────┘
 *          ↑                              ↑                              ↑
 *          │                              │                              │
 *    协议标识符                     Header 序列化结果              Body 序列化结果
 */
@Sharable
public class TransporterEncoder extends MessageToByteEncoder<Transporter> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Transporter transporter, ByteBuf out) {
        if (transporter == null) {
            throw new RemoteException("encode msg is null");
        }
        out.writeByte(Transporter.MAGIC);
        out.writeByte(Transporter.VERSION);

        // write header
        byte[] header = transporter.getHeader().toBytes();
        out.writeInt(header.length);
        out.writeBytes(header);

        // write body
        byte[] body = transporter.getBody();
        out.writeInt(body.length);
        out.writeBytes(body);
    }

}
