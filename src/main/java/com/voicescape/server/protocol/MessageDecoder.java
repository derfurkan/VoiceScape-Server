package com.voicescape.server.protocol;

import com.voicescape.server.ServerConfig;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Decodes length-prefixed frames from the wire.
 * Max frame length is bounded per spec to prevent buffer abuse.
 *
 * Wire format: [length:2 bytes][payload:N bytes]
 * The length field holds the size of the payload only (excludes itself).
 */
public class MessageDecoder extends LengthFieldBasedFrameDecoder
{
    public MessageDecoder()
    {
        super(
            ServerConfig.MAX_FRAME_LENGTH, // maxFrameLength
            0,                              // lengthFieldOffset
            2,                              // lengthFieldLength (u16)
            0,                              // lengthAdjustment
            2                               // initialBytesToStrip (strip the length prefix)
        );
    }
}
