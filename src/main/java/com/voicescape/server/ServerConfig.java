package com.voicescape.server;

public final class ServerConfig {
    public static final int PORT = getProperty("port");
    public static final int MAX_FRAME_LENGTH = 4096;
    public static final int HANDSHAKE_TIMEOUT_MS = getProperty("handshake_timeout_ms");
    public static final int MAX_CONNECTIONS_PER_IP = getProperty("max_connections_ip");
    public static final int GLOBAL_CONNECTION_CEILING = getProperty("max_connections");
    public static final int MAX_AUDIO_PACKETS_PER_SEC = 70;
    public static final int MAX_HASH_UPDATES_PER_SEC = 5;
    public static final int MAX_BANDWIDTH_BPS = 1500000;
    public static final int MAX_FORWARD_CLIENTS = 10;
    public static final int MAX_AUDIO_PAYLOAD_BYTES = 1400;
    public static final int MAX_NEARBY_HASHES = 50;
    public static final int MAX_HASH_LENGTH = 256;
    public static final int MAX_SID_LENGTH = 16;
    public static final long SESSION_TIMEOUT_MS = getProperty("session_timeout_ms");
    public static final long KEY_ROTATION_INTERVAL_MS = getProperty("key_rotation_interval_s") * 1000L;
    public static final int PROTOCOL_VERSION = getProperty("protocol_version");


    private ServerConfig() {
    }

    static int getProperty(String key) {
        try {
            return Integer.parseInt(System.getProperty(key));
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid property: " + key);
        }
    }

}
