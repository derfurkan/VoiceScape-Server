package com.voicescape.server.protocol;

import com.voicescape.server.ServerConfig;
import com.voicescape.server.Session;
import com.voicescape.server.SessionManager;
import com.voicescape.server.UdpCrypto;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class UdpAudioHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final Logger log = LoggerFactory.getLogger(UdpAudioHandler.class);

    private static final ThreadLocal<ByteBuffer> PLAINTEXT_SCRATCH =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(ServerConfig.MAX_AUDIO_PAYLOAD_BYTES));

    private final SessionManager sessionManager;

    public UdpAudioHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();
        InetSocketAddress sender = packet.sender();

        if (buf.readableBytes() < 2) {
            return;
        }

        byte type = buf.readByte();

        switch (type) {
            case PacketTypes.CLIENT_UDP_REGISTER:
                handleUdpRegister(sender, buf);
                break;

            case PacketTypes.CLIENT_AUDIO_FRAME:
                if (buf.readableBytes() < 4) {
                    return;
                }
                int sequenceNumber = buf.readInt();
                int payloadLen = buf.readableBytes();
                if (payloadLen <= 0 || payloadLen > ServerConfig.MAX_AUDIO_PAYLOAD_BYTES) {
                    return;
                }

                processAudioFrame(sender, sequenceNumber, buf, payloadLen);
                break;

            default:
                log.debug("Unknown UDP packet type 0x{} from {}", Integer.toHexString(type & 0xFF), sender);
                break;
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        sessionManager.flushAllChannels();
    }

    private void handleUdpRegister(InetSocketAddress sender, ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return;
        }

        int sessionIdLen = buf.readUnsignedShort();
        if (sessionIdLen > 256 || buf.readableBytes() < sessionIdLen) {
            return;
        }

        byte[] sessionIdBytes = new byte[sessionIdLen];
        buf.readBytes(sessionIdBytes);
        String sessionId = new String(sessionIdBytes, StandardCharsets.UTF_8);
        Session session = sessionManager.getSessionById(sessionId);
        if ((session != null && !session.isHandshakeComplete())) {
            log.debug("Rejected UDP Register (Session not found)");
            return;
        }
        sessionManager.registerUdpAddress(sessionId, sender);
    }

    private void processAudioFrame(InetSocketAddress sender, int sequenceNumber, ByteBuf buf, int payloadLen) {
        Session session = sessionManager.getSessionByUdpAddress(sender);
        if (session == null || !session.isHandshakeComplete() || !session.checkAudioRate()
                || !session.checkBandwidth(payloadLen)) {
            return;
        }

        ByteBuffer encryptedNio = buf.nioBuffer(buf.readerIndex(), payloadLen);
        ByteBuffer plaintext = PLAINTEXT_SCRATCH.get();
        plaintext.clear();

        try {
            UdpCrypto.processInPlace(session.getUdpKeySpec(), sequenceNumber, encryptedNio, plaintext,
                    javax.crypto.Cipher.DECRYPT_MODE);
        } catch (Exception e) {
            log.debug("UDP decrypt failed for session {}", session.getSessionId());
            return;
        }

        plaintext.flip();
        sessionManager.forwardAudio(session, sequenceNumber, plaintext);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("UDP handler exception: {}", cause.getMessage());
    }
}
