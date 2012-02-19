/*
 * Copyright 2011 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vanilla.java.chronicle.impl;

import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;
import vanilla.java.chronicle.ByteString;
import vanilla.java.chronicle.Chronicle;
import vanilla.java.chronicle.Excerpt;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author peter.lawrey
 */
public class UnsafeExcerpt<C extends DirectChronicle> implements Excerpt {
    protected final C chronicle;
    protected long index;
    private long start = 0;
    private long position = 0;
    private int capacity = 0;
    private long limit = 0;

    private long startPosition;
    protected ByteBuffer buffer;
    private boolean forWrite = false;


    protected UnsafeExcerpt(C chronicle) {
        this.chronicle = chronicle;
    }

    @Override
    public Chronicle chronicle() {
        return chronicle;
    }

    @Override
    public boolean index(long index) throws IndexOutOfBoundsException {
        long endPosition = chronicle.getIndexData(index + 1);
        if (endPosition == 0) {
            barrier.get();
            buffer = null;
            return false;
        }
        long startPosition = chronicle.getIndexData(index);
        int capacity = (int) (endPosition - startPosition);
        index0(index, startPosition, endPosition);
        forWrite = false;
        return true;
    }

    @Override
    public void startExcerpt(int capacity) {
        long startPosition = chronicle.startExcerpt(capacity);
        long endPosition = startPosition + capacity;
        index0(chronicle.size(), startPosition, endPosition);
        forWrite = true;
    }


    private void index0(long index, long startPosition, long endPosition) {
        this.index = index;
        this.capacity = capacity;
        this.startPosition = startPosition;

        buffer = chronicle.acquireDataBuffer(startPosition);

        long address = ((DirectBuffer) buffer).address();
        start = position = address + chronicle.positionInBuffer(startPosition);
        limit = address + chronicle.positionInBuffer(endPosition - 1) + 1;
        assert limit > start;
        assert position < limit;
        assert endPosition > startPosition;
    }

    @Override
    public void finish() {
        if (position > limit)
            throw new IllegalStateException("Capacity allowed: " + capacity + " data read/written: " + position);
        if (forWrite) {
            memoryBarrier();
            final long endPosition = startPosition + (position - start);
            chronicle.setIndexData(index + 1, endPosition);
            chronicle.incrSize();
            memoryBarrier();
        }
    }

    final AtomicBoolean barrier = new AtomicBoolean();

    private void memoryBarrier() {
        barrier.lazySet(true);
    }

    @Override
    public long index() {
        return index;
    }

    @Override
    public Excerpt position(int position) {
        if (position < 0 || position >= capacity()) throw new IndexOutOfBoundsException();
        this.position = position;
        return this;
    }

    @Override
    public int position() {
        return (int) (position - start);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void readFully(byte[] b) {
        readFully(b, 0, b.length);
    }

    // RandomDataInput

    @Override
    public int skipBytes(int n) {
        int position = position();
        int n2 = Math.min(n, capacity - position);
        position(position + n2);
        return n2;
    }

    @Override
    public void readFully(byte[] b, int off, int len) {
        while (len-- > 0)
            b[off++] = readByte();
    }


    @Override
    public boolean readBoolean() {
        return readByte() != 0;
    }

    @Override
    public boolean readBoolean(int offset) {
        return readByte(offset) != 0;
    }

    @Override
    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    @Override
    public int readUnsignedByte(int offset) {
        return readByte(offset) & 0xFF;
    }

    @Override
    public int readUnsignedShort() {
        return readShort() & 0xFF;
    }

    @Override
    public int readUnsignedShort(int offset) {
        return readShort(offset) & 0xFF;
    }

    @Override
    public String readLine() {
        StringBuilder input = new StringBuilder();
        EOL:
        while (position() < capacity()) {
            int c = readUnsignedByte();
            switch (c) {
                case '\n':
                    break EOL;
                case '\r':
                    int cur = position();
                    if (cur < capacity() && readByte(cur) == '\n')
                        position(cur + 1);
                    break EOL;
                default:
                    input.append((char) c);
                    break;
            }
        }
        return input.toString();
    }

    @Override
    public String readUTF() {
        try {
            return DataInputStream.readUTF(this);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String readUTF(int offset) {
        long oldPosition = position;
        position = offset;
        try {
            return readUTF();
        } finally {
            position = oldPosition;
        }
    }

    @Override
    public byte readByte() {
        return UNSAFE.getByte(position++);
    }

    @Override
    public byte readByte(int offset) {
        return UNSAFE.getByte(start + offset);
    }

    @Override
    public short readShort() {
        short s = UNSAFE.getShort(position);
        position += 2;
        return s;
    }

    @Override
    public short readShort(int offset) {
        return UNSAFE.getShort(start + offset);
    }

    private static final byte BYTE_MIN_VALUE = Byte.MIN_VALUE;
    private static final byte BYTE_EXTENDED = Byte.MIN_VALUE + 1;
    private static final byte BYTE_MAX_VALUE = Byte.MIN_VALUE + 2;

    private static final short UBYTE_EXTENDED = 0xff;

    @Override
    public short readCompactShort() {
        byte b = readByte();
        switch (b) {
            case BYTE_MIN_VALUE:
                return Short.MIN_VALUE;
            case BYTE_MAX_VALUE:
                return Short.MAX_VALUE;
            case BYTE_EXTENDED:
                return readShort();
            default:
                return b;
        }
    }

    @Override
    public int readCompactUnsignedShort() {
        int b = readUnsignedByte();
        if (b == UBYTE_EXTENDED)
            return readUnsignedShort();
        return b;
    }

    @Override
    public char readChar() {
        char ch = UNSAFE.getChar(position);
        position += 2;
        return ch;
    }

    @Override
    public char readChar(int offset) {
        return UNSAFE.getChar(start + offset);
    }

    @Override
    public int readInt() {
        int i = UNSAFE.getInt(position);
        position += 4;
        return i;
    }

    @Override
    public int readInt(int offset) {
        return UNSAFE.getInt(start + offset);
    }

    @Override
    public long readUnsignedInt() {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public long readUnsignedInt(int offset) {
        return readInt(offset) & 0xFFFFFFFFL;
    }

    private static final short SHORT_MIN_VALUE = Short.MIN_VALUE;
    private static final short SHORT_EXTENDED = Short.MIN_VALUE + 1;
    private static final short SHORT_MAX_VALUE = Short.MIN_VALUE + 2;

    private static final int USHORT_EXTENDED = 0xFFFF;

    @Override
    public int readCompactInt() {
        short b = readShort();
        switch (b) {
            case SHORT_MIN_VALUE:
                return Integer.MIN_VALUE;
            case SHORT_MAX_VALUE:
                return Integer.MAX_VALUE;
            case SHORT_EXTENDED:
                return readInt();
            default:
                return b;
        }
    }


    @Override
    public long readCompactUnsignedInt() {
        int b = readUnsignedByte();
        if (b == USHORT_EXTENDED)
            return readUnsignedInt();
        return b;
    }

    @Override
    public long readLong() {
        long l = UNSAFE.getLong(position);
        position += 8;
        return l;
    }

    @Override
    public long readLong(int offset) {
        return UNSAFE.getLong(start + offset);
    }

    private static final int INT_MIN_VALUE = Integer.MIN_VALUE;
    private static final int INT_EXTENDED = Integer.MIN_VALUE + 1;
    private static final int INT_MAX_VALUE = Integer.MIN_VALUE + 2;

    @Override
    public long readCompactLong() {
        int b = readInt();
        switch (b) {
            case INT_MIN_VALUE:
                return Long.MIN_VALUE;
            case INT_MAX_VALUE:
                return Long.MAX_VALUE;
            case INT_EXTENDED:
                return readLong();
            default:
                return b;
        }
    }

    @Override
    public float readFloat() {
        float f = UNSAFE.getFloat(position);
        position += 4;
        return f;
    }

    @Override
    public float readFloat(int offset) {
        return UNSAFE.getFloat(start + offset);
    }

    @Override
    public double readDouble() {
        double d = UNSAFE.getDouble(position);
        position += 8;
        return d;
    }

    @Override
    public double readDouble(int offset) {
        return UNSAFE.getDouble(start + offset);
    }

    @Override
    public double readCompactDouble() {
        float f = readFloat();
        if (Float.isNaN(f))
            return readDouble();
        return f;
    }

    @Override
    public void readByteString(ByteString as) {
        as.clear();
        int len = readByte() & 0xFF;
        for (int i = 0; i < len; i++)
            as.append(readByte());
    }

    @Override
    public int readByteString(int offset, ByteString as) {
        as.clear();
        int len = readByte(offset) & 0xFF;
        for (int i = 1; i <= len; i++)
            as.append(readByte(offset + i));
        return offset + len + 1;
    }

    @Override
    public void readByteString(StringBuilder sb) {
        sb.setLength(0);
        int len = readByte() & 0xFF;
        for (int i = 0; i < len; i++)
            sb.append(readByte());
    }

    @Override
    public int readByteString(int offset, StringBuilder sb) {
        sb.setLength(0);
        int len = readByte(offset) & 0xFF;
        for (int i = 1; i <= len; i++)
            sb.append(readByte(offset + i));
        return offset + len + 1;
    }

    @Override
    public String readByteString() {
        int len = readByte() & 0xFF;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++)
            bytes[i] = readByte();
        return new String(bytes, 0);
    }

    @Override
    public void readChars(StringBuilder sb) {
        int len = readChar();
        sb.setLength(0);
        for (int i = 0; i < len; i++)
            sb.append(readChar());
    }

    @Override
    public String readChars() {
        int len = readChar();
        if (len == 0) return "";
        char[] chars = new char[len];
        for (int i = 0; i < len; i++)
            chars[i] = readChar();
        return new String(chars);
    }

    //// RandomOutputStream
    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    @Override
    public void writeBoolean(boolean v) {
        write(v ? 0 : -1);
    }

    @Override
    public void writeBoolean(int offset, boolean v) {
        write(offset, v ? 0 : -1);
    }

    @Override
    public void writeBytes(String s) {
        writeBytes((CharSequence) s);
    }

    @Override
    public void writeBytes(CharSequence s) {
        int len = s.length();
        if (len > 255) throw new IllegalArgumentException("Len cannot be " + len + " > 255");
        write(len);
        for (int i = 0; i < len; i++)
            write(s.charAt(i));
    }

    @Override
    public void writeBytes(int offset, CharSequence s) {
        int len = s.length();
        if (len > 255) throw new IllegalArgumentException("Len cannot be " + len + " > 255");
        write(offset, len);
        for (int i = 0; i < len; i++)
            write(s.charAt(i));
        for (int i = 0; i < len; i++)
            write(offset + 1 + i, s.charAt(i));
    }

    @Override
    public void writeChars(String s) {
        writeChars((CharSequence) s);
    }

    @Override
    public void writeChars(CharSequence s) {
        int len = s.length();
        if (len > 65535) throw new IllegalArgumentException("Len cannot be " + len + " > 65535");
        writeChar(len);
        for (int i = 0; i < len; i++)
            writeChar(s.charAt(i));
    }

    @Override
    public void writeChars(int offset, CharSequence s) {
        int len = s.length();
        if (len > 65535) throw new IllegalArgumentException("Len cannot be " + len + " > 65535");
        writeChar(offset + len);
        for (int i = 0; i < len; i++)
            writeChar(offset + 2 + i, s.charAt(i));
    }

    static final Method writeUTFMethod;

    static {
        try {
            writeUTFMethod = DataOutputStream.class.getDeclaredMethod("writeUTF", String.class, DataOutput.class);
            writeUTFMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void writeUTF(String s) {
        try {
            writeUTFMethod.invoke(null, s, this);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            // rethrow the orginal exception.
            Thread.currentThread().stop(e.getCause());
        }
    }

    @Override
    public void write(int b) {
        UNSAFE.putByte(position++, (byte) b);
    }

    @Override
    public void writeByte(int v) {
        write(v);
    }

    @Override
    public void writeUnsignedByte(int v) {
        writeByte(v);
    }

    @Override
    public void write(int offset, int b) {
        UNSAFE.putByte(start + offset, (byte) b);
    }

    @Override
    public void write(int offset, byte[] b) {
        for (int i = 0; i < b.length; i++)
            write(offset + i, b[i]);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++)
            write(b[off + i]);
    }

    @Override
    public void writeShort(int v) {
        UNSAFE.putShort(position, (short) v);
        position += 2;
    }

    @Override
    public void writeShort(int offset, int v) {
        UNSAFE.putShort(start + offset, (short) v);
    }

    @Override
    public void writeUnsignedShort(int v) {
        writeShort(v);
    }

    @Override
    public void writeUnsignedShort(int offset, int v) {
        writeUnsignedShort(offset, v);
    }

    @Override
    public void writeCompactShort(int v) {
        if (v > BYTE_MAX_VALUE && v <= Byte.MAX_VALUE)
            writeByte(v);
        else switch (v) {
            case Short.MIN_VALUE:
                writeByte(BYTE_MIN_VALUE);
                break;
            case Short.MAX_VALUE:
                writeByte(BYTE_MAX_VALUE);
                break;
            default:
                writeByte(BYTE_EXTENDED);
                writeShort(v);
                break;
        }
    }

    @Override
    public void writeCompactUnsignedShort(int v) {
        if (v >= 0 && v < USHORT_EXTENDED) {
            writeByte(v);
        } else {
            writeUnsignedShort(USHORT_EXTENDED);
            writeUnsignedShort(v);
        }
    }

    @Override
    public void writeChar(int v) {
        UNSAFE.putChar(position, (char) v);
        position += 2;
    }

    @Override
    public void writeChar(int offset, int v) {
        UNSAFE.putChar(start + offset, (char) v);
    }

    @Override
    public void writeInt(int v) {
        UNSAFE.putInt(position, v);
        position += 4;
    }

    @Override
    public void writeInt(int offset, int v) {
        UNSAFE.putInt(start + offset, v);
    }

    @Override
    public void writeUnsignedInt(long v) {
        writeInt((int) v);
    }

    @Override
    public void writeUnsignedInt(int offset, long v) {
        writeInt(offset, (int) v);
    }

    @Override
    public void writeCompactInt(int v) {
        if (v > SHORT_MAX_VALUE && v <= Short.MAX_VALUE)
            writeShort(v);
        else switch (v) {
            case Integer.MIN_VALUE:
                writeShort(SHORT_MIN_VALUE);
                break;
            case Integer.MAX_VALUE:
                writeShort(SHORT_MAX_VALUE);
                break;
            default:
                writeShort(BYTE_EXTENDED);
                writeInt(v);
                break;
        }
    }


    @Override
    public void writeCompactUnsignedInt(long v) {
        if (v >= 0 && v < USHORT_EXTENDED) {
            writeShort((int) v);
        } else {
            writeShort(USHORT_EXTENDED);
            writeUnsignedInt(v);
        }
    }

    @Override
    public void writeLong(long v) {
        UNSAFE.putLong(position, v);
        position += 8;
    }

    @Override
    public void writeLong(int offset, long v) {
        UNSAFE.putLong(start + offset, v);
    }

    @Override
    public void writeCompactLong(long v) {
        if (v > INT_MAX_VALUE && v <= Integer.MAX_VALUE) {
            writeInt((int) v);

        } else if (v == Long.MIN_VALUE) {
            writeInt(BYTE_MIN_VALUE);

        } else if (v == Long.MAX_VALUE) {
            writeInt(BYTE_MAX_VALUE);

        } else {
            writeInt(BYTE_EXTENDED);
            writeLong(v);

        }
    }

    @Override
    public void writeFloat(float v) {
        UNSAFE.putFloat(position, v);
        position += 4;
    }

    @Override
    public void writeFloat(int offset, float v) {
        UNSAFE.putFloat(start + offset, v);
    }

    @Override
    public void writeDouble(double v) {
        UNSAFE.putDouble(position, v);
        position += 8;
    }

    @Override
    public void writeDouble(int offset, double v) {
        UNSAFE.putDouble(start + offset, v);
    }

    @Override
    public void writeCompactDouble(double v) {
        float f = (float) v;
        if (f == v) {
            writeFloat(f);
        } else {
            writeFloat(Float.NaN);
            writeDouble(v);
        }
    }

    private static final Unsafe UNSAFE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
