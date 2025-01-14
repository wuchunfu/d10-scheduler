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

package com.github.krystalics.d10.scheduler.rpc.remote;


import com.github.krystalics.d10.scheduler.rpc.client.RpcRequestCache;
import com.github.krystalics.d10.scheduler.rpc.client.RpcRequestTable;
import com.github.krystalics.d10.scheduler.rpc.codec.NettyDecoder;
import com.github.krystalics.d10.scheduler.rpc.codec.NettyEncoder;
import com.github.krystalics.d10.scheduler.rpc.common.RpcRequest;
import com.github.krystalics.d10.scheduler.rpc.common.RpcResponse;
import com.github.krystalics.d10.scheduler.rpc.config.NettyClientConfig;
import com.github.krystalics.d10.scheduler.rpc.future.RpcFuture;
import com.github.krystalics.d10.scheduler.rpc.protocol.RpcProtocol;
import com.github.krystalics.d10.scheduler.rpc.utils.Constants;
import com.github.krystalics.d10.scheduler.rpc.utils.Host;
import com.github.krystalics.d10.scheduler.rpc.utils.NettyUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import net.openhft.affinity.AffinityStrategies;
import net.openhft.affinity.AffinityThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NettyClient
 */
public class NettyClient {

    public static NettyClient getInstance() {
        return NettyClient.NettyClientInner.INSTANCE;
    }

    private static class NettyClientInner {

        private static final NettyClient INSTANCE = new NettyClient(new NettyClientConfig());
    }

    private final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    /**
     * worker group
     */
    private final EventLoopGroup workerGroup;

    /**
     * client config
     */
    private final NettyClientConfig clientConfig;


    /**
     * client bootstrap
     */
    private final Bootstrap bootstrap = new Bootstrap();

    /**
     * started flag
     */
    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    /**
     * channels
     */
    private final ConcurrentHashMap<Host, Channel> channels = new ConcurrentHashMap(128);

    /**
     * get channel
     */
    private Channel getChannel(Host host) {
        Channel channel = channels.get(host);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        return createChannel(host, true);
    }

    /**
     * create channel
     *
     * @param host   host
     * @param isSync sync flag
     * @return channel
     */
    public Channel createChannel(Host host, boolean isSync) {
        ChannelFuture future;
        try {
            synchronized (bootstrap) {
                future = bootstrap.connect(new InetSocketAddress(host.getIp(), host.getPort()));
            }
            if (isSync) {
                future.sync();
            }
            if (future.isSuccess()) {
                Channel channel = future.channel();
                channels.put(host, channel);
                return channel;
            }
        } catch (Exception ex) {
            logger.warn(String.format("connect to %s error", host), ex);
        }
        return null;
    }

    /**
     * client init
     * 使用AffinityThreadFactory让不同的工作线程绑定不同的cpu、减少线程切换的开销
     * 利用的是线程亲和性的特点 https://github.com/OpenHFT/Java-Thread-Affinity#controlling-the-affinity-more-directly
     * @param clientConfig client config
     */
    private NettyClient(final NettyClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        if (NettyUtils.useEpoll()) {
            this.workerGroup = new EpollEventLoopGroup(clientConfig.getWorkerThreads(), new AffinityThreadFactory("NettyClient", AffinityStrategies.DIFFERENT_CORE));
        } else {
            this.workerGroup = new NioEventLoopGroup(clientConfig.getWorkerThreads(), new AffinityThreadFactory("NettyClient", AffinityStrategies.DIFFERENT_CORE));
        }
        this.start();

    }

    /**
     * start
     */
    private void start() {

        this.bootstrap
                .group(this.workerGroup)
                .channel(NettyUtils.getSocketChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, clientConfig.isSoKeepalive())
                .option(ChannelOption.TCP_NODELAY, clientConfig.isTcpNoDelay())
                .option(ChannelOption.SO_SNDBUF, clientConfig.getSendBufferSize())
                .option(ChannelOption.SO_RCVBUF, clientConfig.getReceiveBufferSize())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientConfig.getConnectTimeoutMillis())
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new NettyEncoder())
                                .addLast(new NettyDecoder(RpcResponse.class))
                                .addLast("client-idle-handler", new IdleStateHandler(Constants.NETTY_CLIENT_HEART_BEAT_TIME, 0, 0, TimeUnit.MILLISECONDS))
                                .addLast(new NettyClientHandler());
                    }
                });

        isStarted.compareAndSet(false, true);
    }

    public RpcResponse sendMsg(Host host, RpcProtocol<RpcRequest> protocol, Boolean async) {

        Channel channel = getChannel(host);
        assert channel != null;
        RpcRequest request = protocol.getBody();
        RpcRequestCache rpcRequestCache = new RpcRequestCache();
        String serviceName = request.getClassName() + request.getMethodName();
        rpcRequestCache.setServiceName(serviceName);
        long reqId = protocol.getMsgHeader().getRequestId();
        RpcFuture future = null;
        if (Boolean.FALSE.equals(async)) {
            future = new RpcFuture(request, reqId);
            rpcRequestCache.setRpcFuture(future);
        }
        RpcRequestTable.put(protocol.getMsgHeader().getRequestId(), rpcRequestCache);
        channel.writeAndFlush(protocol);

        RpcResponse result = null;
        if (Boolean.TRUE.equals(async)) {
            result = new RpcResponse();
            result.setStatus((byte) 0);
            //keypoint 异步调用 先返回空值
            result.setResult(null);
            return result;
        }
        try {
            assert future != null;
            result = future.get();
        } catch (InterruptedException e) {
            logger.error("send msg error，service name is {}", serviceName, e);
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /**
     * close
     */
    public void close() {
        if (isStarted.compareAndSet(true, false)) {
            try {
                closeChannels();
                if (workerGroup != null) {
                    this.workerGroup.shutdownGracefully();
                }
            } catch (Exception ex) {
                logger.error("netty client close exception", ex);
            }
            logger.info("netty client closed");
        }
    }

    /**
     * close channels
     */
    private void closeChannels() {
        for (Channel channel : this.channels.values()) {
            channel.close();
        }
        this.channels.clear();
    }
}
