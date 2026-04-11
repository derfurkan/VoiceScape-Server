package com.voicescape.server;

/**
 * All server-tunable limits in one place.
 * Values from the wire protocol spec in CLAUDE.md.
 */
public final class ServerConfig
{
    private ServerConfig() {}

    // ── Network ─────────────────────────────────────────────────
    public static final int PORT = 5555;
    public static final int MAX_FRAME_LENGTH = 1500;
    public static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    // ── Connection limits ───────────────────────────────────────
    public static final int MAX_CONNECTIONS_PER_IP = 3;
    public static final int GLOBAL_CONNECTION_CEILING = 5000;

    // ── Rate limits ─────────────────────────────────────────────
    public static final int MAX_AUDIO_PACKETS_PER_SEC = 70;
    public static final int MAX_HASH_UPDATES_PER_SEC = 5;
    public static final int MAX_BANDWIDTH_BPS = 64_000; // 64 kbps per session

    // ── Payload limits ──────────────────────────────────────────
    public static final int MAX_AUDIO_PAYLOAD_BYTES = 200;
    public static final int MAX_NEARBY_HASHES = 200;
    public static final int MAX_HASH_LENGTH = 256;

    // ── Timing ──────────────────────────────────────────────────
    public static final long SESSION_TIMEOUT_MS = 30_000;
    public static final long KEY_ROTATION_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24 hours
    public static final long HEARTBEAT_EXPECT_INTERVAL_MS = 15_000;

    // ── Protocol ────────────────────────────────────────────────
    public static final int PROTOCOL_VERSION = 1;
}
