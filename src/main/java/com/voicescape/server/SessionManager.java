package com.voicescape.server;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all active sessions and enforces connection limits.
 */
public class SessionManager
{
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<Channel, Session> sessionsByChannel = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByHash = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    private boolean loopbackEnabled = false;

    public void setLoopbackEnabled(boolean enabled)
    {
        this.loopbackEnabled = enabled;
        if (enabled)
        {
            log.warn("Server loopback enabled — audio will be echoed back to senders (testing only)");
        }
    }

    public SessionManager()
    {
        reaper = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "VoiceScape-SessionReaper");
            t.setDaemon(true);
            return t;
        });

        // Reap timed-out sessions every 10 seconds
        reaper.scheduleAtFixedRate(this::reapTimedOutSessions, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Result of a session creation attempt.
     */
    public static class CreateResult
    {
        public final Session session;
        public final String rejectReason;

        private CreateResult(Session session, String rejectReason)
        {
            this.session = session;
            this.rejectReason = rejectReason;
        }

        public boolean isSuccess() { return session != null; }

        static CreateResult success(Session session) { return new CreateResult(session, null); }
        static CreateResult rejected(String reason)  { return new CreateResult(null, reason); }
    }

    /**
     * Attempt to create a session for a new connection.
     * Returns a result containing the session on success, or a reject reason on failure.
     */
    public CreateResult createSession(Channel channel)
    {
        // Global ceiling
        if (sessionsByChannel.size() >= ServerConfig.GLOBAL_CONNECTION_CEILING)
        {
            log.warn("Global connection ceiling reached ({}), rejecting", ServerConfig.GLOBAL_CONNECTION_CEILING);
            return CreateResult.rejected("Server is full");
        }

        // Per-IP limit — increment first, then check, roll back if over limit
        String ip = getIp(channel);
        AtomicInteger count = connectionsPerIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > ServerConfig.MAX_CONNECTIONS_PER_IP)
        {
            count.decrementAndGet();
            log.warn("Per-IP limit reached for {} ({}), rejecting", ip, ServerConfig.MAX_CONNECTIONS_PER_IP);
            return CreateResult.rejected("Too many connections from your IP");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Session session = new Session(sessionId, channel);
        sessionsByChannel.put(channel, session);

        log.debug("Session created: {} from {}", sessionId, ip);
        return CreateResult.success(session);
    }

    public void removeSession(Channel channel)
    {
        Session session = sessionsByChannel.remove(channel);
        if (session != null)
        {
            String hash = session.getIdentityHash();
            if (hash != null)
            {
                sessionsByHash.remove(hash, session);
            }

            String ip = getIp(channel);
            AtomicInteger count = connectionsPerIp.get(ip);
            if (count != null)
            {
                int remaining = count.decrementAndGet();
                if (remaining <= 0)
                {
                    connectionsPerIp.remove(ip);
                }
            }
            log.debug("Session removed: {}", session.getSessionId());
        }
    }

    /**
     * Update the hash→session index when a session's identity hash changes.
     */
    public void updateHashIndex(Session session, String oldHash, String newHash)
    {
        if (oldHash != null)
        {
            sessionsByHash.remove(oldHash, session);
        }
        if (newHash != null)
        {
            sessionsByHash.put(newHash, session);
        }
    }

    public Session getSession(Channel channel)
    {
        return sessionsByChannel.get(channel);
    }

    public Map<Channel, Session> getAllSessions()
    {
        return sessionsByChannel;
    }

    public int getActiveSessionCount()
    {
        return sessionsByChannel.size();
    }

    /**
     * Core routing rule:
     * Forward audio from sender S to receiver R iff:
     *   S's nearbyHashes contains R's identityHash
     *   AND R's nearbyHashes contains S's identityHash
     * (Mutual vouching)
     */
    public void forwardAudio(Session sender, int sequenceNumber, byte[] opusPayload)
    {
        String senderHash = sender.getIdentityHash();
        if (senderHash == null)
        {
            return;
        }

        // Server loopback: echo audio back to the sender for full-path testing
        if (loopbackEnabled)
        {
            sendAudioToReceiver(sender, senderHash, sequenceNumber, opusPayload);
        }

        // Use the hash index for O(K) lookup instead of O(N) session scan,
        // where K = number of nearby hashes the sender reports.
        for (String candidateHash : sender.getNearbyHashes())
        {
            Session receiver = sessionsByHash.get(candidateHash);
            if (receiver == null || receiver == sender)
            {
                continue;
            }

            if (!receiver.isHandshakeComplete())
            {
                continue;
            }

            // Mutual vouching: sender already lists candidateHash (by iteration),
            // now check that receiver also lists sender
            if (receiver.getNearbyHashes().contains(senderHash))
            {
                sendAudioToReceiver(receiver, senderHash, sequenceNumber, opusPayload);
            }
        }
    }

    private void sendAudioToReceiver(Session receiver, String senderHash, int sequenceNumber, byte[] opusPayload)
    {
        Channel channel = receiver.getChannel();
        if (!channel.isActive())
        {
            return;
        }

        // Build server audio frame:
        // [type:1][hashLen:2][hash:N][seq:4][payloadLen:2][payload:N]
        byte[] hashBytes = senderHash.getBytes(StandardCharsets.UTF_8);
        int totalLen = 1 + 2 + hashBytes.length + 4 + 2 + opusPayload.length;

        io.netty.buffer.ByteBuf buf = channel.alloc().buffer(totalLen);
        buf.writeByte(com.voicescape.server.protocol.PacketTypes.SERVER_AUDIO_FRAME);
        buf.writeShort(hashBytes.length);
        buf.writeBytes(hashBytes);
        buf.writeInt(sequenceNumber);
        buf.writeShort(opusPayload.length);
        buf.writeBytes(opusPayload);

        channel.writeAndFlush(buf);
    }

    public void broadcastKeyRotation(byte[] newKey)
    {
        for (Session session : sessionsByChannel.values())
        {
            if (!session.isHandshakeComplete())
            {
                continue;
            }

            Channel channel = session.getChannel();
            if (!channel.isActive())
            {
                continue;
            }

            io.netty.buffer.ByteBuf buf = channel.alloc().buffer(1 + 2 + newKey.length);
            buf.writeByte(com.voicescape.server.protocol.PacketTypes.SERVER_KEY_ROTATION);
            buf.writeShort(newKey.length);
            buf.writeBytes(newKey);

            channel.writeAndFlush(buf);
        }
    }

    public void shutdown()
    {
        reaper.shutdownNow();
        for (Channel channel : sessionsByChannel.keySet())
        {
            channel.close();
        }
        sessionsByChannel.clear();
        sessionsByHash.clear();
        connectionsPerIp.clear();
    }

    private void reapTimedOutSessions()
    {
        for (Map.Entry<Channel, Session> entry : sessionsByChannel.entrySet())
        {
            if (entry.getValue().isTimedOut())
            {
                log.debug("Reaping timed-out session: {}", entry.getValue().getSessionId());
                entry.getKey().close();
            }
        }
    }

    private String getIp(Channel channel)
    {
        InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
        return addr.getAddress().getHostAddress();
    }
}
