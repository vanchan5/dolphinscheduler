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

import org.apache.dolphinscheduler.extract.base.StandardRpcRequest;
import org.apache.dolphinscheduler.extract.base.StandardRpcResponse;
import org.apache.dolphinscheduler.extract.base.serialize.JsonSerializer;

import java.io.Serializable;

import lombok.Data;
import lombok.NonNull;

/**
 * ByteBuf:
 * ┌──────┬─────────┬──────────────┬──────────────────┬──────────────┬──────────────────┐
 * │ MAGIC│ VERSION │ HEADER_LEN   │ HEADER_BYTES      │ BODY_LEN     │ BODY_BYTES       │
 * │(1字节)│(1字节)  │  (4字节)     │ (变长，JSON)      │  (4字节)     │ (变长，JSON)     │
 * └──────┴─────────┴──────────────┴──────────────────┴──────────────┴──────────────────┘
 *          ↑                              ↑                              ↑
 *          │                              │                              │
 *    协议标识符                     Header 序列化结果              Body 序列化结果
 *
 * Transporter 设计分析：解决粘包和拆包问题
 * 一、TCP 粘包和拆包问题
 *  1. 问题本质
 *      TCP 是流式协议，没有消息边界：
 * 应用层发送：
 * Message1: [A|B|C]
 * Message2: [D|E|F]
 *
 * TCP 层可能接收为：
 * 情况1（粘包）：[A|B|C|D|E|F]  ← 两个消息粘在一起
 * 情况2（拆包）：[A|B] [C|D|E|F]  ← 一个消息被拆成两段
 * 情况3（混合）：[A|B|C|D] [E|F]  ← 粘包+拆包混合
 *
 * 2. 为什么会出现？
 * TCP 缓冲区：发送方可能合并多个小包
 * 网络 MTU：大包会被分片
 * 接收缓冲区：接收方可能一次读取多个包
 *
 */
@Data
public class Transporter implements Serializable {

    /**
     * 作用：
     *      禁用 Java 序列化：-1L 表示不使用标准 Java 序列化
     *      使用自定义协议：通过 TransporterEncoder/Decoder 处理
     *      避免版本冲突：不依赖 Java 序列化版本号
     * 为什么需要：
     *      Transporter 实现了 Serializable，但实际不用于 Java 序列化
     *      可能用于其他场景（如日志、缓存），但网络传输使用自定义协议
     *      -1L 明确表示不使用标准序列化
     */
    private static final long serialVersionUID = -1L;

    /**
     * 作用：消息边界标识符
     *  1、问题场景：如何识别消息的开始？
     *   1.1 没有MAGIC：
     *      ByteBuf: [???|???|???|...]  ← 不知道从哪里开始
     *
     *   1.2 有 MAGIC：
     *      ByteBuf: [0xbabe|VERSION|...]  ← 明确标识消息开始
     *
     *  2、解决粘包问题
     *    2.1 场景：两个消息粘在一起
     *      ByteBuf: [0xbabe|V1|...|0xbabe|V2|...]
     *               ↑                    ↑
     *            消息1开始            消息2开始
     *
     *  解码器流程：
     *  1. 读取第一个 0xbabe → 识别消息1开始
     *  2. 解析完整消息1
     *  3. 继续读取 → 发现下一个 0xbabe → 识别消息2开始
     *  4. 解析完整消息2
     */
    public static final byte MAGIC = (byte) 0xbabe;

    /**
     *  作用：协议版本控制
     *
     * 场景：协议升级
     * 旧版本：MAGIC|VERSION=0|...
     * 新版本：MAGIC|VERSION=1|...
     *
     * 解码器可以：
     * 1. 读取 MAGIC 确认是协议数据
     * 2. 读取 VERSION 判断协议版本
     * 3. 使用对应的解码逻辑
     */
    public static final byte VERSION = 0;

    /**
     * header 是对象，body 是 byte[] 的设计
     * 设计原因：
     *
     * 1、Header 需要快速访问：
     *   // 在内存中，header 是对象，可以直接访问
     *   String method = transporter.getHeader().getMethodIdentifier();
     *   long opaque = transporter.getHeader().getOpaque();
     * 2、Body 延迟反序列化
     *   // body 是 byte[]，只在需要时才反序列化
     *   // 避免不必要的序列化/反序列化开销
     *   StandardRpcRequest request = JsonSerializer.deserialize(body, StandardRpcRequest.class);
     */
    private TransporterHeader header;
    private byte[] body;

    public static Transporter of(@NonNull TransporterHeader header, StandardRpcResponse iRpcResponse) {
        return of(header, JsonSerializer.serialize(iRpcResponse));
    }

    public static Transporter of(@NonNull TransporterHeader header, StandardRpcRequest iRpcRequest) {
        return of(header, JsonSerializer.serialize(iRpcRequest));
    }

    public static Transporter of(@NonNull TransporterHeader header, byte[] body) {
        Transporter transporter = new Transporter();
        transporter.setHeader(header);
        transporter.setBody(body);
        return transporter;
    }

}
