package com.voicescape.server.protocol;

import com.voicescape.server.DailyKeyManager;
import com.voicescape.server.ServerConfig;
import com.voicescape.server.Session;
import com.voicescape.server.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles decoded messages from clients.
 * Each channel gets its own handler instance (not @Sharable).
 */
public class MessageHandler extends SimpleChannelInboundHandler<ByteBuf>
{
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final SessionManager sessionManager;
    private final DailyKeyManager keyManager;

    public MessageHandler(SessionManager sessionManager, DailyKeyManager keyManager)
    {
        this.sessionManager = sessionManager;
        this.keyManager = keyManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception
    {
        SessionManager.CreateResult result = sessionManager.createSession(ctx.channel());
        if (!result.isSuccess())
        {
            sendErrorAndClose(ctx, result.rejectReason);
            return;
        }

        // Start handshake timeout
        ctx.executor().schedule(() ->
        {
            Session s = sessionManager.getSession(ctx.channel());
            if (s != null && !s.isHandshakeComplete())
            {
                log.debug("Handshake timeout for session {}", s.getSessionId());
                sendErrorAndClose(ctx, "Handshake timed out");
            }
        }, ServerConfig.HANDSHAKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception
    {
        sessionManager.removeSession(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception
    {
        Session session = sessionManager.getSession(ctx.channel());
        if (session == null)
        {
            ctx.close();
            return;
        }

        if (msg.readableBytes() < 1)
        {
            return;
        }

        byte type = msg.readByte();

        switch (type)
        {
            case PacketTypes.CLIENT_HELLO:
                handleHello(ctx, session, msg);
                break;

            case PacketTypes.CLIENT_HASH_LIST:
                handleHashListUpdate(ctx, session, msg);
                break;

            default:
                log.warn("Unknown message type 0x{} from session {}", Integer.toHexString(type & 0xFF), session.getSessionId());
                sendErrorAndClose(ctx, "Unknown message type");
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
    {
        log.debug("Channel exception: {}", cause.getMessage());
        ctx.close();
    }

    /**
     * Send a SERVER_ERROR message to the client, then close the connection.
     */
    private void sendErrorAndClose(ChannelHandlerContext ctx, String message)
    {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ctx.alloc().buffer(1 + 2 + msgBytes.length);
        buf.writeByte(PacketTypes.SERVER_ERROR);
        buf.writeShort(msgBytes.length);
        buf.writeBytes(msgBytes);
        ctx.writeAndFlush(buf).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private void handleHello(ChannelHandlerContext ctx, Session session, ByteBuf msg)
    {
        if (msg.readableBytes() < 6) // protocolVersion(4) + hashLen(2)
        {
            log.warn("Hello too short from session {}", session.getSessionId());
            sendErrorAndClose(ctx, "Malformed handshake");
            return;
        }

        int protocolVersion = msg.readInt();
        if (protocolVersion != ServerConfig.PROTOCOL_VERSION)
        {
            log.warn("Unsupported protocol version {} from session {}", protocolVersion, session.getSessionId());
            sendErrorAndClose(ctx, "Unsupported protocol version (expected v" + ServerConfig.PROTOCOL_VERSION + ")");
            return;
        }

        int hashLen = msg.readUnsignedShort();
        if (hashLen > ServerConfig.MAX_HASH_LENGTH || msg.readableBytes() < hashLen)
        {
            log.warn("Invalid identity hash length {} from session {}", hashLen, session.getSessionId());
            sendErrorAndClose(ctx, "Invalid identity hash");
            return;
        }

        byte[] hashBytes = new byte[hashLen];
        msg.readBytes(hashBytes);
        String identityHash = new String(hashBytes, StandardCharsets.UTF_8);

        String oldHash = session.getIdentityHash();
        session.setIdentityHash(identityHash);
        sessionManager.updateHashIndex(session, oldHash, identityHash);

        if (session.isHandshakeComplete())
        {
            // Re-identification (after key rotation or two-phase handshake) —
            // update the identity hash but don't re-send HelloAck.
            log.debug("Identity re-sent for session {}", session.getSessionId());
            return;
        }

        session.setHandshakeComplete(true);

        // Send HelloAck only on the initial handshake
        sendHelloAck(ctx, session);

        log.debug("Handshake complete for session {}", session.getSessionId());
    }

    private void sendHelloAck(ChannelHandlerContext ctx, Session session)
    {
        byte[] sessionIdBytes = session.getSessionId().getBytes(StandardCharsets.UTF_8);
        byte[] currentKey = keyManager.getCurrentKey();
        byte[] previousKey = keyManager.getPreviousKey();
        byte[] udpKey = session.getUdpKey();

        int totalLen = 1  // type
            + 2 + sessionIdBytes.length
            + 2 + currentKey.length
            + 2 + previousKey.length
            + 2 + udpKey.length;

        ByteBuf buf = ctx.alloc().buffer(totalLen);
        buf.writeByte(PacketTypes.SERVER_HELLO_ACK);
        buf.writeShort(sessionIdBytes.length);
        buf.writeBytes(sessionIdBytes);
        buf.writeShort(currentKey.length);
        buf.writeBytes(currentKey);
        buf.writeShort(previousKey.length);
        buf.writeBytes(previousKey);
        buf.writeShort(udpKey.length);
        buf.writeBytes(udpKey);

        ctx.writeAndFlush(buf);
    }

    private void handleHashListUpdate(ChannelHandlerContext ctx, Session session, ByteBuf msg)
    {
        if (!session.isHandshakeComplete())
        {
            sendErrorAndClose(ctx, "Handshake not complete");
            return;
        }

        if (!session.checkHashUpdateRate())
        {
            log.debug("Hash update rate limit exceeded for session {}", session.getSessionId());
            return; // Silently drop, don't disconnect
        }

        if (msg.readableBytes() < 2)
        {
            sendErrorAndClose(ctx, "Malformed hash list");
            return;
        }

        int count = msg.readUnsignedShort();
        if (count > ServerConfig.MAX_NEARBY_HASHES)
        {
            log.warn("Too many hashes ({}) from session {}", count, session.getSessionId());
            sendErrorAndClose(ctx, "Too many nearby hashes");
            return;
        }

        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < count; i++)
        {
            if (msg.readableBytes() < 2)
            {
                sendErrorAndClose(ctx, "Malformed hash list");
                return;
            }

            int hashLen = msg.readUnsignedShort();
            if (hashLen > ServerConfig.MAX_HASH_LENGTH || msg.readableBytes() < hashLen)
            {
                sendErrorAndClose(ctx, "Malformed hash list");
                return;
            }

            byte[] hashBytes = new byte[hashLen];
            msg.readBytes(hashBytes);
            hashes.add(new String(hashBytes, StandardCharsets.UTF_8));
        }

        session.updateNearbyHashes(hashes);
    }

}
