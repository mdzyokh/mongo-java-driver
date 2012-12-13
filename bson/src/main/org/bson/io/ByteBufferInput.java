/*
 * Copyright (c) 2008 - 2012 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bson.io;

import org.bson.BsonType;
import org.bson.types.ObjectId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

public class ByteBufferInput implements InputBuffer {
    private static final Charset UTF8_CHARSET = Charset.forName("UTF8");

    private final ByteBuffer buffer;

    public ByteBufferInput(final ByteBuffer buffer) {
        this.buffer = buffer;
        buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public int getPosition() {
        return buffer.position();
    }

    @Override
    public boolean readBoolean() {
        return buffer.get() == 0x1;
    }

    @Override
    public byte readByte() {
        return buffer.get();
    }

    @Override
    public byte[] readBytes(final int size) {
        // TODO: should we really allocate byte array here?
        final byte[] bytes = new byte[size];
        buffer.get(bytes);
        return bytes;
    }

    @Override
    public long readInt64() {
        return buffer.getLong();
    }

    @Override
    public double readDouble() {
        return buffer.getDouble();
    }

    @Override
    public int readInt32() {
        return buffer.getInt();
    }

    @Override
    public String readString() {
        final int size = readInt32();
        final byte[] bytes = readBytes(size);
        // TODO: this is 1.6 only...
        return new String(bytes, 0, size - 1, UTF8_CHARSET);
    }

    @Override
    public ObjectId readObjectId() {
        return new ObjectId(readBigEndianInt(), readBigEndianInt(), readBigEndianInt());
    }

    @Override
    public BsonType readBsonType() {
        return BsonType.findByValue(buffer.get());
    }

    @Override
    public String readCString() {
        // TODO: potentially optimize this
        final int mark = buffer.position();
        readUntilNullByte();
        final int size = buffer.position() - mark - 1;
        buffer.position(mark);

        final byte[] bytes = readBytes(size);
        readByte();  // read the trailing null bytes

        // TODO: this is 1.6 only...
        return new String(bytes, UTF8_CHARSET);
    }

    private void readUntilNullByte() {
        while (buffer.get() != 0) {
            ;
        }
    }

    @Override
    public void skipCString() {
        readUntilNullByte();
    }

    @Override
    public void setPosition(final int position) {
        buffer.position(position);
    }

    private int readBigEndianInt() {
        int x = 0;
        x |= (0xFF & buffer.get()) << 24;
        x |= (0xFF & buffer.get()) << 16;
        x |= (0xFF & buffer.get()) << 8;
        x |= (0xFF & buffer.get());
        return x;
    }
}