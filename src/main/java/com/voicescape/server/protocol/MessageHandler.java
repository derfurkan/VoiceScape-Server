package com.voicescape.server.protocol;

import com.voicescape.server.KeyManager;
import com.voicescape.server.ServerConfig;
import com.voicescape.server.Session;
import com.voicescape.server.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class MessageHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final SessionManager sessionManager;
    private final KeyManager keyManager;

    public MessageHandler(SessionManager sessionManager, KeyManager keyManager) {
        this.sessionManager = sessionManager;
        this.keyManager = keyManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SessionManager.CreateResult result = sessionManager.createSession(ctx.channel());
        if (!result.isSuccess()) {
            sendErrorAndClose(ctx, result.rejectReason);
            return;
        }

        ctx.executor().schedule(() -> {
            Session s = sessionManager.getSession(ctx.channel());
            if (s != null && !s.isHandshakeComplete()) {
                log.debug("Handshake timeout for session {}", s.getSessionId());
                sendErrorAndClose(ctx, "Handshake timed out");
            }
        }, ServerConfig.HANDSHAKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Reaper takes care of this
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        Session session = sessionManager.getSession(ctx.channel());
        if (session == null) {
            ctx.close();
            return;
        }

        if (msg.readableBytes() < 1) {
            return;
        }

        byte type = msg.readByte();

        switch (type) {
            case PacketTypes.CLIENT_HELLO:
                handleHello(ctx, session, msg);
                break;

            case PacketTypes.CLIENT_IDENTITY:
                handleIdentity(ctx, session, msg);
                break;

            case PacketTypes.CLIENT_HASH_LIST:
                handleHashListUpdate(ctx, session, msg);
                break;

            default:
                log.warn("Unknown message type 0x{} from session {}", Integer.toHexString(type & 0xFF),
                        session.getSessionId());
                sendErrorAndClose(ctx, "Unknown message type");
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Channel exception: {}", cause.getMessage());
        ctx.close();
    }

    private void sendErrorAndClose(ChannelHandlerContext ctx, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ctx.alloc().buffer(1 + 2 + msgBytes.length);
        buf.writeByte(PacketTypes.SERVER_ERROR);
        buf.writeShort(msgBytes.length);
        buf.writeBytes(msgBytes);
        ctx.writeAndFlush(buf).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private void handleHello(ChannelHandlerContext ctx, Session session, ByteBuf msg) {
        if (session.isHelloReceived()) {
            log.warn("Duplicate Hello from session {}", session.getSessionId());
            sendErrorAndClose(ctx, "Hello already received");
            return;
        }

        if (msg.readableBytes() < 6) {
            log.warn("Hello too short from session {}", session.getSessionId());
            sendErrorAndClose(ctx, "Malformed handshake");
            return;
        }

        int protocolVersion = msg.readInt();
        if (protocolVersion != ServerConfig.PROTOCOL_VERSION) {
            log.warn("Unsupported protocol version {} from session {}", protocolVersion, session.getSessionId());
            sendErrorAndClose(ctx, "Unsupported protocol version (expected v" + ServerConfig.PROTOCOL_VERSION + ")");
            return;
        }

        int sessionIdLen = msg.readUnsignedShort();
        if (sessionIdLen > ServerConfig.MAX_SID_LENGTH || msg.readableBytes() < sessionIdLen) {
            log.warn("Invalid SessionId length {} from session {}", sessionIdLen, session.getSessionId());
            sendErrorAndClose(ctx, "Invalid SessionId");
            return;
        }

        byte[] sessionIdBytes = new byte[sessionIdLen];
        msg.readBytes(sessionIdBytes);
        String clientSessionId = new String(sessionIdBytes, StandardCharsets.UTF_8);

        if (sessionIdLen > 0) {
            Session existingSession = sessionManager.getSessionById(clientSessionId);
            if (existingSession != null && existingSession != session) {
                sessionManager.removeSession(ctx.channel());
                sessionManager.rebindSession(ctx.channel(), existingSession);
                session = existingSession;
                session.setHelloReceived(true);
                log.debug("Session {} reclaimed on new channel", clientSessionId);
                sendHelloAck(ctx, session);
                return;
            } else if (existingSession == null) {
                log.debug("Client attempted to reclaim but SessionId {} does not exist.", clientSessionId);
            }
        }

        session.setHelloReceived(true);
        sendHelloAck(ctx, session);
    }

    /**
     * Step 3: CLIENT_IDENTITY — client registers its identity hash.
     * Format: [hashLen:2][hash]
     * Also used after key rotation to update the hash.
     */
    private void handleIdentity(ChannelHandlerContext ctx, Session session, ByteBuf msg) {
        if (!session.isHelloReceived()) {
            sendErrorAndClose(ctx, "Hello not received");
            return;
        }

        if (msg.readableBytes() < 2) {
            sendErrorAndClose(ctx, "Malformed identity");
            return;
        }

        int hashLen = msg.readUnsignedShort();
        if (hashLen == 0 || hashLen > ServerConfig.MAX_HASH_LENGTH || msg.readableBytes() < hashLen) {
            log.warn("Invalid identity hash length {} from session {}", hashLen, session.getSessionId());
            sendErrorAndClose(ctx, "Invalid identity hash");
            return;
        }

        byte[] hashBytes = new byte[hashLen];
        msg.readBytes(hashBytes);
        String identityHash = new String(hashBytes, StandardCharsets.UTF_8);

        if (!sessionManager.claimHash(session, identityHash)) {
            sendErrorAndClose(ctx, "Identity already claimed");
            return;
        }

        String oldHash = session.getIdentityHash();
        session.setIdentityHash(identityHash);
        sessionManager.updateHashIndex(session, oldHash, identityHash);

        session.setHandshakeComplete(true);
        log.debug("Identity registered for session {}, handshake complete", session.getSessionId());

    }

    private void sendHelloAck(ChannelHandlerContext ctx, Session session) {
        byte[] sessionIdBytes = session.getSessionId().getBytes(StandardCharsets.UTF_8);
        byte[] currentKey = keyManager.getCurrentKey();
        byte[] udpKey = session.getUdpKeySpec().getEncoded();

        int totalLen = 1
                + 2 + sessionIdBytes.length
                + 2 + currentKey.length
                + 2 + udpKey.length;

        ByteBuf buf = ctx.alloc().buffer(totalLen);
        buf.writeByte(PacketTypes.SERVER_HELLO_ACK);
        buf.writeShort(sessionIdBytes.length);
        buf.writeBytes(sessionIdBytes);
        buf.writeShort(currentKey.length);
        buf.writeBytes(currentKey);
        buf.writeShort(udpKey.length);
        buf.writeBytes(udpKey);

        ctx.writeAndFlush(buf);
    }

    private void handleHashListUpdate(ChannelHandlerContext ctx, Session session, ByteBuf msg) {
        if (!session.isHandshakeComplete()) {
            sendErrorAndClose(ctx, "Handshake not complete");
            return;
        }

        if (!session.checkHashUpdateRate()) {
            log.debug("Hash update rate limit exceeded for session {}", session.getSessionId());
            return;
        }

        if (msg.readableBytes() < 2) {
            sendErrorAndClose(ctx, "Malformed hash list");
            return;
        }

        int count = msg.readUnsignedShort();
        if (count > ServerConfig.MAX_NEARBY_HASHES) {
            log.warn("Too many hashes ({}) from session {}", count, session.getSessionId());
            sendErrorAndClose(ctx, "Too many nearby hashes");
            return;
        }

        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (msg.readableBytes() < 2) {
                sendErrorAndClose(ctx, "Malformed hash list");
                return;
            }

            int hashLen = msg.readUnsignedShort();
            if (hashLen > ServerConfig.MAX_HASH_LENGTH || msg.readableBytes() < hashLen) {
                sendErrorAndClose(ctx, "Malformed hash list");
                return;
            }

            byte[] hashBytes = new byte[hashLen];
            msg.readBytes(hashBytes);
            hashes.add(new String(hashBytes, StandardCharsets.UTF_8));
        }

        session.updateNearbyHashes(hashes);

        Set<Session> newMutuals = new HashSet<>();
        for (String candidateHash : session.getNearbyHashes()) {
            if (newMutuals.size() >= ServerConfig.MAX_FORWARD_CLIENTS) {
                break;
            }
            Session other = sessionManager.getSessionByHash(candidateHash);
            if (other != null && other != session && other.isHandshakeComplete()) {
                // Only add to THIS session's mutual set if the other party also has us
                if (other.getNearbyHashes().contains(session.getIdentityHash())) {
                    newMutuals.add(other);
                }
            }
        }

        session.getMutualNearbySessions().clear();
        session.getMutualNearbySessions().addAll(newMutuals);
    }
}
