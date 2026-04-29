package com.voicescape.server;

import com.voicescape.server.protocol.PacketTypes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<Channel, Session> sessionsByChannel = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByHash = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsBySessionId = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Session> sessionsByUdpAddress = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private final boolean loopback;
    private final PacedSender pacedSender;

    private final List<DatagramChannel> udpSendChannels = new CopyOnWriteArrayList<>();

    public SessionManager(boolean loopback, PacedSender pacedSender) {
        this.loopback = loopback;
        this.pacedSender = pacedSender;
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "VoiceScape-SessionReaper");
            thread.setDaemon(true);
            return thread;
        });

        reaper.scheduleAtFixedRate(this::reapTimedOutSessions, 10, 10, TimeUnit.SECONDS);
    }

    public void addUdpSendChannel(DatagramChannel channel) {
        udpSendChannels.add(channel);
    }

    private DatagramChannel channelFor(Session session) {
        int count = udpSendChannels.size();
        if (count == 0) return null;
        if (count == 1) return udpSendChannels.getFirst();
        return udpSendChannels.get((session.getSessionId().hashCode() & 0x7FFFFFFF) % count);
    }

    public CreateResult createSession(Channel channel) {
        if (sessionsByChannel.size() >= ServerConfig.GLOBAL_CONNECTION_CEILING) {
            return CreateResult.rejected("Server is full");
        }

        String ip = getIp(channel);
        boolean[] rejected = {false};
        connectionsPerIp.compute(ip, (k, count) -> {
            if (count == null) count = new AtomicInteger(0);
            if (count.get() >= ServerConfig.MAX_CONNECTIONS_PER_IP) {
                rejected[0] = true;
                return count;
            }
            count.incrementAndGet();
            return count;
        });

        if (rejected[0]) {
            return CreateResult.rejected("Too many connections from your IP");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Session session = new Session(sessionId, channel);
        sessionsByChannel.put(channel, session);
        sessionsBySessionId.put(sessionId, session);

        log.debug("Session created: {} from {}", sessionId, ip);
        return CreateResult.success(session);
    }

    public void removeSession(Channel channel) {
        Session session = sessionsByChannel.remove(channel);
        if (session != null) {
            sessionsBySessionId.remove(session.getSessionId());
            pacedSender.removeFlow(session.getSessionId());

            String hash = session.getIdentityHash();
            if (hash != null) {
                sessionsByHash.remove(hash, session);
            }

            InetSocketAddress udpAddr = session.getUdpAddress();
            if (udpAddr != null) {
                sessionsByUdpAddress.remove(udpAddr);
            }

            // Clean up mutual neighbor references in other sessions
            for (Session mutual : session.getMutualNearbySessions()) {
                mutual.getMutualNearbySessions().remove(session);
            }

            String ip = getIp(channel);
            connectionsPerIp.compute(ip, (k, count) -> {
                if (count == null) return null;
                return count.decrementAndGet() <= 0 ? null : count;
            });

            log.debug("Session removed: {}", session.getSessionId());
        }
    }

    public void rebindSession(Channel channel, Session session) {
        Channel oldChannel = session.getChannel();
        if (oldChannel != null && oldChannel != channel) {
            sessionsByChannel.remove(oldChannel, session);
        }
        session.updateChannel(channel);
        sessionsByChannel.put(channel, session);
    }

    public void updateHashIndex(Session session, String oldHash, String newHash) {
        if (oldHash != null) {
            sessionsByHash.remove(oldHash, session);
        }
        if (newHash != null) {
            sessionsByHash.put(newHash, session);
        }
    }

    public void registerUdpAddress(String sessionId, InetSocketAddress address) {
        Session session = sessionsBySessionId.get(sessionId);
        if (session == null) {
            log.debug("UDP registration for unknown session: {}", sessionId);
            return;
        }

        InetSocketAddress oldAddr = session.getUdpAddress();
        if (oldAddr != null) {
            sessionsByUdpAddress.remove(oldAddr);
        }

        session.setUdpAddress(address);
        sessionsByUdpAddress.put(address, session);
        if (oldAddr == null || !oldAddr.equals(address)) {
            log.debug("UDP address registered for session {}: {}", sessionId, address);
        }

    }

    public boolean claimHash(Session session, String hash) {
        Session existing = sessionsByHash.putIfAbsent(hash, session);
        return existing == null || existing == session;
    }

    public Session getSessionByHash(String hash) {
        return sessionsByHash.get(hash);
    }

    public Session getSessionByUdpAddress(InetSocketAddress address) {
        return sessionsByUdpAddress.get(address);
    }

    public Session getSession(Channel channel) {
        return sessionsByChannel.get(channel);
    }

    public Session getSessionById(String sessionId) {
        return sessionsBySessionId.get(sessionId);
    }

    public void forwardAudio(Session sender, int sequenceNumber, ByteBuffer plaintext) {
        byte[] senderHashBytes = sender.getIdentityHashBytes();
        if (senderHashBytes == null) {
            return;
        }

        if (udpSendChannels.isEmpty()) {
            return;
        }

        if (loopback) {
            DatagramChannel ch = channelFor(sender);
            if (ch != null && ch.isWritable()) {
                sendAudioToReceiver(ch, sender, senderHashBytes, sequenceNumber, plaintext);
            }
            return;
        }

        for (Session receiver : sender.getMutualNearbySessions()) {
            // Re-verify handshake because sessions might have disconnected but not yet reaped
            if (!receiver.isHandshakeComplete()) continue;

            DatagramChannel ch = channelFor(receiver);
            if (ch != null) {
                sendAudioToReceiver(ch, receiver, senderHashBytes, sequenceNumber, plaintext);
            }
        }
    }

    public void flushAllChannels() {
        for (DatagramChannel ch : udpSendChannels) {
            ch.flush();
        }
    }

    private void sendAudioToReceiver(DatagramChannel ds, Session receiver, byte[] senderHashBytes,
                                     int sequenceNumber, ByteBuffer plaintext) {
        InetSocketAddress udpAddr = receiver.getUdpAddress();
        if (udpAddr == null) {
            return;
        }

        int payloadLen = plaintext.limit();

        // Calculate total length: type(1) + hashLen(2) + hash + seq(4) + payload
        int totalLen = 1 + 2 + senderHashBytes.length + 4 + payloadLen;
        ByteBuf buf = ds.alloc().directBuffer(totalLen);

        buf.writeByte(PacketTypes.SERVER_AUDIO_FRAME);
        buf.writeShort(senderHashBytes.length);
        buf.writeBytes(senderHashBytes);
        buf.writeInt(sequenceNumber);

        // Encrypt directly into the direct ByteBuf, reading from the shared plaintext scratch
        ByteBuffer outNio = buf.nioBuffer(buf.writerIndex(), payloadLen);
        plaintext.position(0);
        try {
            UdpCrypto.processInPlace(receiver.getUdpKeySpec(), sequenceNumber, plaintext, outNio, javax.crypto.Cipher.ENCRYPT_MODE);
            buf.writerIndex(buf.writerIndex() + payloadLen);
        } catch (Exception e) {
            buf.release();
            return;
        }

        pacedSender.enqueue(receiver.getSessionId(), ds, buf, udpAddr);
    }

    public void broadcastKeyRotation(byte[] newKey) {
        for (Session session : sessionsByChannel.values()) {
            if (!session.isHandshakeComplete()) {
                continue;
            }

            Channel channel = session.getChannel();
            if (!channel.isActive()) {
                continue;
            }

            ByteBuf buf = channel.alloc().buffer(1 + 2 + newKey.length);
            buf.writeByte(PacketTypes.SERVER_KEY_ROTATION);
            buf.writeShort(newKey.length);
            buf.writeBytes(newKey);
            channel.writeAndFlush(buf);
        }
    }

    public void shutdown() {
        reaper.shutdownNow();
        for (DatagramChannel ch : udpSendChannels) {
            ch.close();
        }
        udpSendChannels.clear();
        for (Channel channel : sessionsByChannel.keySet()) {
            channel.close();
        }
        sessionsByChannel.clear();
        sessionsByHash.clear();
        sessionsBySessionId.clear();
        sessionsByUdpAddress.clear();
        connectionsPerIp.clear();
    }

    private void reapTimedOutSessions() {
        List<Channel> toReap = new ArrayList<>();
        for (Map.Entry<Channel, Session> entry : sessionsByChannel.entrySet()) {
            if (entry.getValue().isTimedOut()) {
                toReap.add(entry.getKey());
            }
        }
        for (Channel ch : toReap) {
            removeSession(ch);
            ch.close();
        }
    }


    public Map<String, Session> getSessionsBySessionId() {
        return sessionsBySessionId;
    }

    public List<DatagramChannel> getUdpSendChannels() {
        return udpSendChannels;
    }

    private String getIp(Channel channel) {
        InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
        return addr.getAddress().getHostAddress();
    }

    // Session creation result
    public static class CreateResult {
        public final Session session;
        public final String rejectReason;

        private CreateResult(Session session, String rejectReason) {
            this.session = session;
            this.rejectReason = rejectReason;
        }

        static CreateResult success(Session session) {
            return new CreateResult(session, null);
        }

        static CreateResult rejected(String reason) {
            return new CreateResult(null, reason);
        }

        public boolean isSuccess() {
            return session != null;
        }
    }
}
