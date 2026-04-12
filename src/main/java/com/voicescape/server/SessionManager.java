package com.voicescape.server;

import io.netty.channel.Channel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
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
    private final Map<String, Session> sessionsBySessionId = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Session> sessionsByUdpAddress = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    // Each worker thread gets its own DatagramSocket for outbound sends.
    // This avoids funneling all writes through one Netty event loop thread.
    private static final ThreadLocal<DatagramSocket> SEND_SOCKET = ThreadLocal.withInitial(() ->
    {
        try
        {
            return new DatagramSocket();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create UDP send socket", e);
        }
    });

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
        sessionsBySessionId.put(sessionId, session);

        log.debug("Session created: {} from {}", sessionId, ip);
        return CreateResult.success(session);
    }

    public void removeSession(Channel channel)
    {
        Session session = sessionsByChannel.remove(channel);
        if (session != null)
        {
            sessionsBySessionId.remove(session.getSessionId());

            String hash = session.getIdentityHash();
            if (hash != null)
            {
                sessionsByHash.remove(hash, session);
            }

            InetSocketAddress udpAddr = session.getUdpAddress();
            if (udpAddr != null)
            {
                sessionsByUdpAddress.remove(udpAddr);
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

    public void registerUdpAddress(String sessionId, InetSocketAddress address)
    {
        Session session = sessionsBySessionId.get(sessionId);
        if (session == null)
        {
            log.debug("UDP registration for unknown session: {}", sessionId);
            return;
        }

        // Remove old mapping if address changed
        InetSocketAddress oldAddr = session.getUdpAddress();
        if (oldAddr != null)
        {
            sessionsByUdpAddress.remove(oldAddr);
        }

        session.setUdpAddress(address);
        sessionsByUdpAddress.put(address, session);
        if(oldAddr == null || !oldAddr.equals(address)) {
    log.debug("UDP address registered for session {}: {}", sessionId, address);
        }
    
    }

    public Session getSessionByUdpAddress(InetSocketAddress address)
    {
        return sessionsByUdpAddress.get(address);
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

        // Pre-build the header once for all receivers:
        // [type:1][hashLen:2][hash:N][seq:4]
        byte[] senderHashBytes = senderHash.getBytes(StandardCharsets.UTF_8);
        byte[] header = buildAudioHeader(senderHashBytes, sequenceNumber);
        if (header == null)
        {
            return;
        }

        DatagramSocket ds = SEND_SOCKET.get();

        // Server loopback: echo audio back to the sender for full-path testing
        if (loopbackEnabled)
        {
            sendAudioToReceiver(ds, sender, header, sequenceNumber, opusPayload);
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
                sendAudioToReceiver(ds, receiver, header, sequenceNumber, opusPayload);
            }
        }
    }

    /**
     * Build the fixed portion of the outbound audio packet header.
     * Reused for every receiver of the same sender's frame.
     */
    private byte[] buildAudioHeader(byte[] senderHashBytes, int sequenceNumber)
    {
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1 + 2 + senderHashBytes.length + 4);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(com.voicescape.server.protocol.PacketTypes.SERVER_AUDIO_FRAME);
            out.writeShort(senderHashBytes.length);
            out.write(senderHashBytes);
            out.writeInt(sequenceNumber);
            return baos.toByteArray();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * Encrypt and send an audio frame directly via DatagramSocket.
     * Called from worker threads — each thread uses its own socket,
     * so no serialization bottleneck.
     */
    private void sendAudioToReceiver(DatagramSocket ds, Session receiver, byte[] header,
                                     int sequenceNumber, byte[] opusPayload)
    {
        InetSocketAddress udpAddr = receiver.getUdpAddress();
        if (udpAddr == null)
        {
            return;
        }

        byte[] encrypted;
        try
        {
            encrypted = UdpCrypto.encrypt(receiver.getUdpKey(), sequenceNumber, opusPayload);
        }
        catch (Exception e)
        {
            return;
        }

        // Combine header + encrypted payload into one packet
        byte[] packet = new byte[header.length + encrypted.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(encrypted, 0, packet, header.length, encrypted.length);

        try
        {
            ds.send(new DatagramPacket(packet, packet.length, udpAddr));
        }
        catch (IOException e)
        {
            log.debug("UDP send failed for session {}: {}", receiver.getSessionId(), e.getMessage());
        }
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
        sessionsBySessionId.clear();
        sessionsByUdpAddress.clear();
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
