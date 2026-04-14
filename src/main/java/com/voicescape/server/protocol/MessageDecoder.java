package com.voicescape.server.protocol;

import com.voicescape.server.ServerConfig;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

public class MessageDecoder extends LengthFieldBasedFrameDecoder {
    public MessageDecoder() {
        super(
                ServerConfig.MAX_FRAME_LENGTH, // maxFrameLength
                0,                              // lengthFieldOffset
                2,                              // lengthFieldLength (u16)
                0,                              // lengthAdjustment
                2                               // initialBytesToStrip (strip the length prefix)
        );
    }
}
