package com.voicescape.server;

import io.netty.channel.Channel;

import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Session {
    private final String sessionId;
    private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong rateLimitWindowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong audioPacketCount = new AtomicLong(0);
    private final AtomicLong hashUpdateCount = new AtomicLong(0);
    private final AtomicLong bytesThisSecond = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> activeSpeakers = new ConcurrentHashMap<>();
    private final SecretKeySpec udpKeySpec;
    private volatile Channel channel;
    private volatile String identityHash;
    private volatile Set<String> nearbyHashes = ConcurrentHashMap.newKeySet();
    private volatile boolean helloReceived = false;
    private volatile boolean handshakeComplete = false;
    private volatile InetSocketAddress udpAddress;

    public Session(String sessionId, Channel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        byte[] udpKey = new byte[32];
        new SecureRandom().nextBytes(udpKey);
        this.udpKeySpec = new SecretKeySpec(udpKey, 0, 16, "AES");
    }

    public SecretKeySpec getUdpKeySpec() {
        return udpKeySpec;
    }

    // Recode this
    public boolean canReceiveFrom(String senderHash) {
        long now = System.currentTimeMillis();

        if (activeSpeakers.get(senderHash) != null) {
            activeSpeakers.put(senderHash, now);
            return true;
        }

        if (activeSpeakers.size() >= ServerConfig.MAX_FORWARD_CLIENTS) {
            activeSpeakers.entrySet().removeIf(e -> now - e.getValue() >= ServerConfig.FORWARD_CLIENT_TIMEOUT_MS);
        }

        if (activeSpeakers.size() >= ServerConfig.MAX_FORWARD_CLIENTS) {
            return false;
        }

        activeSpeakers.put(senderHash, now);
        return true;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getIdentityHash() {
        return identityHash;
    }

    public void setIdentityHash(String identityHash) {
        this.identityHash = identityHash;
    }

    public Set<String> getNearbyHashes() {
        return nearbyHashes;
    }

    public void updateNearbyHashes(Set<String> hashes) {
        Set<String> replacement = ConcurrentHashMap.newKeySet();
        int count = 0;
        for (String hash : hashes) {
            if (count >= ServerConfig.MAX_NEARBY_HASHES) {
                break;
            }
            replacement.add(hash);
            count++;
        }
        nearbyHashes = replacement;
        lastUpdateTime.set(System.currentTimeMillis());
    }

    public void updateChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isHelloReceived() {
        return helloReceived;
    }

    public void setHelloReceived(boolean received) {
        this.helloReceived = received;
    }

    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    public void setHandshakeComplete(boolean complete) {
        this.handshakeComplete = complete;
    }

    public boolean checkAudioRate() {
        resetWindowIfNeeded();
        return audioPacketCount.incrementAndGet() <= ServerConfig.MAX_AUDIO_PACKETS_PER_SEC;
    }

    public boolean checkHashUpdateRate() {
        resetWindowIfNeeded();
        return hashUpdateCount.incrementAndGet() <= ServerConfig.MAX_HASH_UPDATES_PER_SEC;
    }

    public boolean checkBandwidth(int bytes) {
        resetWindowIfNeeded();
        return bytesThisSecond.addAndGet(bytes) <= ServerConfig.MAX_BANDWIDTH_BPS / 8;
    }

    public InetSocketAddress getUdpAddress() {
        return udpAddress;
    }

    public void setUdpAddress(InetSocketAddress udpAddress) {
        this.udpAddress = udpAddress;
    }

    public boolean isTimedOut() {
        return System.currentTimeMillis() - lastUpdateTime.get() > ServerConfig.SESSION_TIMEOUT_MS;
    }

    private void resetWindowIfNeeded() {
        long now = System.currentTimeMillis();
        long windowStart = rateLimitWindowStart.get();
        if (now - windowStart >= 1000) {
            if (rateLimitWindowStart.compareAndSet(windowStart, now)) {
                audioPacketCount.set(0);
                hashUpdateCount.set(0);
                bytesThisSecond.set(0);
            }
        }
    }
}
