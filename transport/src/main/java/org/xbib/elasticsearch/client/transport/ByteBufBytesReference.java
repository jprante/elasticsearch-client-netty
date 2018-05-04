package org.xbib.elasticsearch.client.transport;

import io.netty.buffer.ByteBuf;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ByteBufBytesReference extends BytesReference {

    private final ByteBuf buffer;
    private final int length;
    private final int offset;

    ByteBufBytesReference(ByteBuf buffer, int length) {
        this.buffer = buffer;
        this.length = length;
        this.offset = buffer.readerIndex();
        assert length <= buffer.readableBytes() : "length[" + length +"] > " + buffer.readableBytes();
    }

    @Override
    public byte get(int index) {
        return buffer.getByte(offset + index);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public BytesReference slice(int from, int length) {
        return new ByteBufBytesReference(buffer.slice(offset + from, length), length);
    }

    @Override
    public StreamInput streamInput() {
        return new ByteBufStreamInput(buffer.duplicate(), length);
    }

    @Override
    public void writeTo(OutputStream os) throws IOException {
        buffer.getBytes(offset, os, length);
    }

    ByteBuf toByteBuf() {
        return buffer.duplicate();
    }

    @Override
    public String utf8ToString() {
        return buffer.toString(offset, length, StandardCharsets.UTF_8);
    }

    @Override
    public BytesRef toBytesRef() {
        if (buffer.hasArray()) {
            return new BytesRef(buffer.array(), buffer.arrayOffset() + offset, length);
        }
        final byte[] copy = new byte[length];
        buffer.getBytes(offset, copy);
        return new BytesRef(copy);
    }

    @Override
    public long ramBytesUsed() {
        return buffer.capacity();
    }

}
