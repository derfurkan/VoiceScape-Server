package com.voicescape.server;

/**
 * All server-tunable limits in one place.
 */
public final class ServerConfig
{
    private ServerConfig() {}

    // ── Network ─────────────────────────────────────────────────
    public static final int PORT = getProperty("port");
    public static final int MAX_FRAME_LENGTH = getProperty("max_frame_length");
    public static final int HANDSHAKE_TIMEOUT_MS = getProperty("handshake_timeout_ms");

    // ── Connection limits ───────────────────────────────────────
    public static final int MAX_CONNECTIONS_PER_IP = getProperty("max_connections_ip");
    public static final int GLOBAL_CONNECTION_CEILING = getProperty("max_connections");

    // ── Rate limits ─────────────────────────────────────────────
    public static final int MAX_AUDIO_PACKETS_PER_SEC = getProperty("max_audio_packets_s");
    public static final int MAX_HASH_UPDATES_PER_SEC = getProperty("max_hash_packets_s");
    public static final int MAX_BANDWIDTH_BPS = getProperty("max_bps");

    public static final int MAX_FORWARD_CLIENTS = getProperty("max_forward_clients");
    public static final int FORWARD_CLIENT_TIMEOUT_MS = getProperty("forward_client_timeout");

    // ── Payload limits ──────────────────────────────────────────
    public static final int MAX_AUDIO_PAYLOAD_BYTES = getProperty("max_audio_packet_bytes");
    public static final int MAX_NEARBY_HASHES = getProperty("max_nearby_hashes");
    public static final int MAX_HASH_LENGTH = getProperty("max_hash_packets_length");
    public static final int MAX_SID_LENGTH = getProperty("max_sessionid_length");

    // ── Timing ──────────────────────────────────────────────────
    public static final long SESSION_TIMEOUT_MS = getProperty("session_timeout_ms");
    public static final long KEY_ROTATION_INTERVAL_MS = getProperty("key_rotation_interval_ms");

    // ── Protocol ────────────────────────────────────────────────
    public static final int PROTOCOL_VERSION = getProperty("protocol_version");

    static int getProperty(String key) {
        return Integer.parseInt(System.getProperty(key));
    }

}
