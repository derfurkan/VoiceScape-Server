package com.voicescape.server;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection session state.  Stored in RAM only, wiped on disconnect.
 * No identifying info is ever written to disk or logs.
 */
public class Session
{
    private final String sessionId;
    private final Channel channel;
    private volatile String identityHash;
    private volatile Set<String> nearbyHashes = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());

    // Lock-free rate limiting: window start is packed into an AtomicLong.
    // Counters reset lazily when any check detects the window has elapsed.
    private final AtomicLong rateLimitWindowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong audioPacketCount = new AtomicLong(0);
    private final AtomicLong hashUpdateCount = new AtomicLong(0);
    private final AtomicLong bytesThisSecond = new AtomicLong(0);

    private volatile boolean handshakeComplete = false;
    private volatile InetSocketAddress udpAddress;
    private final byte[] udpKey;

    public Session(String sessionId, Channel channel)
    {
        this.sessionId = sessionId;
        this.channel = channel;
        this.udpKey = new byte[32];
        new SecureRandom().nextBytes(udpKey);
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public Channel getChannel()
    {
        return channel;
    }

    public String getIdentityHash()
    {
        return identityHash;
    }

    public void setIdentityHash(String identityHash)
    {
        this.identityHash = identityHash;
    }

    public Set<String> getNearbyHashes()
    {
        return nearbyHashes;
    }

    public void updateNearbyHashes(Set<String> hashes)
    {
        // Build a new set and swap atomically to avoid a window where
        // the set is empty (which would break concurrent forwardAudio reads).
        Set<String> replacement = ConcurrentHashMap.newKeySet();
        int count = 0;
        for (String hash : hashes)
        {
            if (count >= ServerConfig.MAX_NEARBY_HASHES)
            {
                break;
            }
            replacement.add(hash);
            count++;
        }
        nearbyHashes = replacement;
        lastUpdateTime.set(System.currentTimeMillis());
    }

    public long getLastUpdateTime()
    {
        return lastUpdateTime.get();
    }

    public boolean isHandshakeComplete()
    {
        return handshakeComplete;
    }

    public void setHandshakeComplete(boolean complete)
    {
        this.handshakeComplete = complete;
    }

    /**
     * Check and increment rate limit counter.  Returns true if the action is allowed.
     * Lock-free: uses CAS to reset the window. If two threads race to reset,
     * one wins and the other just increments against the fresh counters — allowing
     * a few extra packets at most, which is fine for audio rate limiting.
     */
    public boolean checkAudioRate()
    {
        resetWindowIfNeeded();
        return audioPacketCount.incrementAndGet() <= ServerConfig.MAX_AUDIO_PACKETS_PER_SEC;
    }

    public boolean checkHashUpdateRate()
    {
        resetWindowIfNeeded();
        return hashUpdateCount.incrementAndGet() <= ServerConfig.MAX_HASH_UPDATES_PER_SEC;
    }

    public boolean checkBandwidth(int bytes)
    {
        resetWindowIfNeeded();
        return bytesThisSecond.addAndGet(bytes) <= ServerConfig.MAX_BANDWIDTH_BPS / 8;
    }

    public byte[] getUdpKey()
    {
        return udpKey;
    }

    public InetSocketAddress getUdpAddress()
    {
        return udpAddress;
    }

    public void setUdpAddress(InetSocketAddress udpAddress)
    {
        this.udpAddress = udpAddress;
    }

    public boolean isTimedOut()
    {
        return System.currentTimeMillis() - lastUpdateTime.get() > ServerConfig.SESSION_TIMEOUT_MS;
    }

    private void resetWindowIfNeeded()
    {
        long now = System.currentTimeMillis();
        long windowStart = rateLimitWindowStart.get();
        if (now - windowStart >= 1000)
        {
            // CAS ensures only one thread resets; losers just proceed
            if (rateLimitWindowStart.compareAndSet(windowStart, now))
            {
                audioPacketCount.set(0);
                hashUpdateCount.set(0);
                bytesThisSecond.set(0);
            }
        }
    }
}
