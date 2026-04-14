package com.voicescape.server;

import com.voicescape.server.protocol.MessageDecoder;
import com.voicescape.server.protocol.MessageEncoder;
import com.voicescape.server.protocol.MessageHandler;
import com.voicescape.server.protocol.UdpAudioHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceScapeServer {
    private static final Logger log = LoggerFactory.getLogger(VoiceScapeServer.class);

    private final int port;
    private final boolean loopback;
    private final SessionManager sessionManager;
    private final DailyKeyManager keyManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventLoopGroup udpGroup;
    private ExecutorService audioWorkers;

    public VoiceScapeServer(int port, boolean loopback) {
        this.port = port;
        this.loopback = loopback;
        this.sessionManager = new SessionManager(loopback);
        this.keyManager = new DailyKeyManager();
    }

    public static void main(String[] args) {
        int port = ServerConfig.PORT;
        boolean loopback = false;

        for (String arg : args) {
            if (arg.equals("--loopback")) {
                loopback = true;
            } else {
                try {
                    port = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    log.error("Unknown argument: {}", arg);
                    System.exit(1);
                }
            }
        }

        VoiceScapeServer server = new VoiceScapeServer(port, loopback);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "VoiceScape-Shutdown"));

        try {
            server.start();
        } catch (Exception e) {
            log.error("Server failed to start", e);
            System.exit(1);
        }
    }

    public void start() throws Exception {
        keyManager.setOnRotation(() -> sessionManager.broadcastKeyRotation(keyManager.getCurrentKey()));
        keyManager.startRotationSchedule();
        boolean isEpollAvailable = Epoll.isAvailable();
        bossGroup = isEpollAvailable ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        workerGroup = isEpollAvailable ? new EpollEventLoopGroup(Runtime.getRuntime().availableProcessors())
                : new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());


        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(isEpollAvailable ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 100)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("decoder", new MessageDecoder());
                        ch.pipeline().addLast("encoder", new MessageEncoder());
                        ch.pipeline().addLast("handler", new MessageHandler(sessionManager, keyManager));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();

        udpGroup = isEpollAvailable
                ? new EpollEventLoopGroup(Runtime.getRuntime().availableProcessors())
                : new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        int udpChannelCount = isEpollAvailable ? Runtime.getRuntime().availableProcessors() * 2 : 1;

        audioWorkers = Executors.newFixedThreadPool(
                Math.max(8, Runtime.getRuntime().availableProcessors()), r ->
                {
                    Thread t = new Thread(r, "VoiceScape-AudioWorker");
                    t.setDaemon(true);
                    return t;
                }
        );

        for (int i = 0; i < udpChannelCount; i++) {
            Bootstrap udpBootstrap = new Bootstrap();
            udpBootstrap.group(udpGroup)
                    .channel(isEpollAvailable ? EpollDatagramChannel.class : NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, false)
                    .option(ChannelOption.SO_RCVBUF, 2000 * 1024)
                    .option(ChannelOption.SO_SNDBUF, 2000 * 1024)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

            if (isEpollAvailable) {
                udpBootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
            }

            udpBootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline().addLast("handler", new UdpAudioHandler(sessionManager, audioWorkers));
                }
            });

            DatagramChannel udpCh = (DatagramChannel) udpBootstrap.bind(port).sync().channel();
            sessionManager.addUdpSendChannel(udpCh);
        }

        log.info("VoiceScape server started on port {}", port);
        log.info("  Netty Epoll available: {} ({})", isEpollAvailable, isEpollAvailable ? "" : Epoll.unavailabilityCause().getCause().getMessage());
        log.info("  UDP send channels: {}", udpChannelCount);
        log.info("  Max connections: {}", ServerConfig.GLOBAL_CONNECTION_CEILING);
        log.info("  Max per IP: {}", ServerConfig.MAX_CONNECTIONS_PER_IP);
        log.info("  Protocol version: {}", ServerConfig.PROTOCOL_VERSION);


        if (loopback) {
            log.warn("  Loopback: ENABLED - audio only echoed back to senders (testing only!)");
        }

        future.channel().closeFuture().sync();
    }

    public void shutdown() {
        log.info("Shutting down VoiceScape server");
        keyManager.shutdown();
        sessionManager.shutdown();
        if (audioWorkers != null) audioWorkers.shutdownNow();
        if (udpGroup != null) udpGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
