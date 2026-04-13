package com.voicescape.server.protocol;

public final class PacketTypes
{
    private PacketTypes() {}

    // Client → Server
    public static final byte CLIENT_HELLO         = 0x01;
    public static final byte CLIENT_HASH_LIST     = 0x02;
    public static final byte CLIENT_AUDIO_FRAME   = 0x03;
    public static final byte CLIENT_IDENTITY      = 0x04;

    // Server → Client
    public static final byte SERVER_HELLO_ACK     = 0x10;
    public static final byte SERVER_KEY_ROTATION  = 0x11;
    public static final byte SERVER_AUDIO_FRAME   = 0x12;
    public static final byte SERVER_ERROR         = 0x13;
}
