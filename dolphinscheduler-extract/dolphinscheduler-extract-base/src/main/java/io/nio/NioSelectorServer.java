package io.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * @Author vanchan
 * @Date 2025/12/24 15:42
 * @Version 1.0
 * description：
 *
 * NIO 有三大核心组件： Channel(通道)， Buffer(缓冲区)，Selector(多路复用器)
 * 1、channel 类似于流，每个 channel 对应一个 buffer缓冲区，buffer 底层就是个数组
 * 2、channel 会注册到 selector 上，由 selector 根据 channel 读写事件的发生将其交由某个空闲的线程处理
 * 3、NIO 的 Buffer 和 channel 都是既可以读也可以写
 *
 *
 *NioSelectorServer 代码里如下几个方法非常重要，我们从Hotspot与Linux内核函数级别来理解下
 * 1 Selector.open() //创建多路复用器
 * 2 socketChannel.register(selector, SelectionKey.OP_READ) //将channel注册到多路复用器上
 * 3 selector.select() //阻塞等待需要处理的事件发生
 */
public class NioSelectorServer {
    public static void main(String[] args) throws IOException, InterruptedException {

        // 创建NIO ServerSocketChannel
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.socket().bind(new InetSocketAddress(9000));
        // 设置ServerSocketChannel为非阻塞
        serverSocket.configureBlocking(false);
        // 打开Selector处理Channel，即创建epoll
        Selector selector = Selector.open();
        /**
         *  SelectionKey.OP_ACCEPT 连接操作
         *  SelectionKey.OP_READ 读事件
         *
         *  register其实就是linux版本jdk下的EPollArrayWrapper.add(fd),fd代表的是ServerSocketChannel的文件描述符
         *
         * 1、文件描述符(file descriptor)，Linux内核为高效管理已被打开的“文件”所创建的索引，用该索引可以找到文件
         *
         *  可以下载openjdk源码查看
         *
         *主要三个函数
         * 1、 private native int epollCreate();
         *     1.1 创建了Epoll实例，用文件描述符epfd代表
         *
         *2、private native void epollCtl(int epfd, int opcode, int fd, int events);
         *     调用native方法epollCtl()进行事件绑定
         *      2.1 epfd: epoll_filed_desc,private native int epollCreate()创建的epoll
         *      2.2 fd:   filed_desc(socketChannel)
         *      2.3 events:   事件: 连接、读、写
         *      2.4 客户端向服务端发送信息,服务器第一个感知,
         *          监听到channel上有事件发生,调用一个中断程序,会将该channel挪到⭐就绪列表rdlist⭐
         *          这个过程是内核级别处理的
         *
         *3、private native int epollWait(long pollAddress, int numfds, long timeout,
         *                                  int epfd) throws IOException;
         *      3.1 调用native方法epollWait()等待,文件描述符epfd(就是epoll实例)上的事件
         *      3.2 当socket收到数据后，⭐中断程序⭐调用回调函数会给epoll实例的事件⭐就绪列表rdlist⭐里添加该socket引用(这块是操作系统实现的)，
         *          操作系统监听rdlist列表
         *          当程序执行到epoll_wait时，如果rdlist已经引用了socket，那么epoll_wait直接返回，如果rdlist为空，阻塞进程
         *      3.3 中断是系统用来响应硬件设备请求的-种机制，操作系统收到硬件的中断请求，会打断正在执行的进程，然后调用内核中的中断处理程序来响应请求。
         *
         * ⭐
         * serverSocket.register(selector, SelectionKey.OP_ACCEPT);
         * 1、调用了EPollArrayWrapper.add(fd),维护一个注册ServerSocketChannel的列表
         * 2、调用private native void epollCtl(int epfd, int opcode, int fd, int events);
         *    epoll绑定socketChannel,监听channel上的事件(操作系统内核函数实现,java实现不了)
         *      绑定接收事件
         *
         * ⭐
         */
        // 把ServerSocketChannel注册到selector上，并且selector对客户端accept连接操作感兴趣
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("服务启动成功");

        while (true) {
            // 阻塞等待需要处理的事件发生,底层调用
            // private native int epollWait(long pollAddress, int numfds, long timeout,int epfd) throws IOException;
            // 就绪列表rdlist有值返回,唤醒;无值则继续阻塞进程
            selector.select();

            // 获取selector中注册的全部事件的 SelectionKey 实例
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();

            // 遍历SelectionKey对事件进行处理
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                // 如果是OP_ACCEPT事件，则进行连接获取和事件注册
                if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = server.accept();
                    socketChannel.configureBlocking(false);
                    // 这里只注册了读事件，如果需要给客户端发送数据可以注册写事件
                    socketChannel.register(selector, SelectionKey.OP_READ);
                    System.out.println("客户端连接成功");
                } else if (key.isReadable()) { // 如果是OP_READ事件，则进行读取和打印
                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    ByteBuffer byteBuffer = ByteBuffer.allocate(128);
                    int len = socketChannel.read(byteBuffer);
                    // 如果有数据，把数据打印出来
                    if (len > 0) {
                        System.out.println("接收到消息：" + new String(byteBuffer.array()));
                    } else if (len == -1){ // 如果客户端断开连接，关闭Socket
                        System.out.println("客户端断开连接");
                        socketChannel.close();
                    }
                }
                // 从事件集合里删除本次处理的key，防止下次select重复处理
                iterator.remove();
            }
        }
    }
}