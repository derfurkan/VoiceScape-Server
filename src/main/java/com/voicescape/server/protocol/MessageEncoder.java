package com.voicescape.server.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prepends a 2-byte length prefix to outgoing messages.
 *
 * Wire format: [length:2 bytes][payload:N bytes]
 */
public class MessageEncoder extends MessageToByteEncoder<ByteBuf>
{
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception
    {
        int length = msg.readableBytes();
        out.writeShort(length);
        out.writeBytes(msg);
    }
}
