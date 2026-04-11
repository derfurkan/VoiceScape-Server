package com.voicescape.server;

import com.voicescape.server.protocol.MessageDecoder;
import com.voicescape.server.protocol.MessageEncoder;
import com.voicescape.server.protocol.MessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoiceScapeServer
{
    private static final Logger log = LoggerFactory.getLogger(VoiceScapeServer.class);

    private final int port;
    private final boolean loopback;
    private final SessionManager sessionManager;
    private final DailyKeyManager keyManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public VoiceScapeServer(int port, boolean loopback)
    {
        this.port = port;
        this.loopback = loopback;
        this.sessionManager = new SessionManager();
        this.keyManager = new DailyKeyManager();
    }

    public void start() throws Exception
    {
        sessionManager.setLoopbackEnabled(loopback);
        keyManager.setOnRotation(() -> sessionManager.broadcastKeyRotation(keyManager.getCurrentKey()));
        keyManager.startRotationSchedule();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // TLS context — expects cert.pem and key.pem in working directory
        // For development/testing, you can generate self-signed certs:
        //   openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes
        SslContext sslContext = buildSslContext();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>()
            {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception
                {
                    if (sslContext != null)
                    {
                        ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                    }
                    ch.pipeline().addLast("decoder", new MessageDecoder());
                    ch.pipeline().addLast("encoder", new MessageEncoder());
                    ch.pipeline().addLast("handler", new MessageHandler(sessionManager, keyManager));
                }
            });

        ChannelFuture future = bootstrap.bind(port).sync();
        log.info("VoiceScape server started on port {}", port);
        log.info("  Max connections: {}", ServerConfig.GLOBAL_CONNECTION_CEILING);
        log.info("  Max per IP: {}", ServerConfig.MAX_CONNECTIONS_PER_IP);
        log.info("  Protocol version: {}", ServerConfig.PROTOCOL_VERSION);

        if (sslContext == null)
        {
            log.warn("  TLS: DISABLED (no cert.pem/key.pem found) - dev mode only!");
        }
        else
        {
            log.info("  TLS: enabled");
        }

        if (loopback)
        {
            log.warn("  Loopback: ENABLED - audio echoed back to senders (testing only!)");
        }

        // Block until server channel closes
        future.channel().closeFuture().sync();
    }

    public void shutdown()
    {
        log.info("Shutting down VoiceScape server");
        keyManager.shutdown();
        sessionManager.shutdown();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    private SslContext buildSslContext()
    {
        File certFile = new File("cert.pem");
        File keyFile = new File("key.pem");

        if (!certFile.exists() || !keyFile.exists())
        {
            log.warn("TLS certificate files not found (cert.pem, key.pem), running without TLS");
            return null;
        }

        try
        {
            return SslContextBuilder.forServer(certFile, keyFile).build();
        }
        catch (Exception e)
        {
            log.error("Failed to initialize TLS, running without it", e);
            return null;
        }
    }

    public static void main(String[] args)
    {
        int port = ServerConfig.PORT;
        boolean loopback = false;

        for (String arg : args)
        {
            if (arg.equals("--loopback"))
            {
                loopback = true;
            }
            else
            {
                try
                {
                    port = Integer.parseInt(arg);
                }
                catch (NumberFormatException e)
                {
                    log.error("Unknown argument: {}", arg);
                    System.exit(1);
                }
            }
        }

        VoiceScapeServer server = new VoiceScapeServer(port, loopback);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "VoiceScape-Shutdown"));

        try
        {
            server.start();
        }
        catch (Exception e)
        {
            log.error("Server failed to start", e);
            System.exit(1);
        }
    }
}
