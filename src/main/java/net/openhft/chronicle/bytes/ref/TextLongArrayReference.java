/*
 * Copyright (c) 2016-2022 chronicle.software
 *
 *     https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.bytes.ref;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.util.DecoratedBufferOverflowException;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.values.LongValue;
import org.jetbrains.annotations.NotNull;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
/**
 * TextLongArrayReference is an implementation of ByteableLongArrayValues that stores long arrays
 * in text format, keeping track of array capacity and values.
 * <p>
 * The text format for a long array is as follows:
 * { capacity: 12345678901234567890, values: [ 12345678901234567890, ... ] }
 */
 @SuppressWarnings("rawtypes")
public class TextLongArrayReference extends AbstractReference implements ByteableLongArrayValues {
    private static final byte[] SECTION1 = "{ locked: false, capacity: ".getBytes(ISO_8859_1);
    private static final byte[] SECTION2 = ", used: ".getBytes(ISO_8859_1);
    private static final byte[] SECTION3 = ", values: [ ".getBytes(ISO_8859_1);
    private static final byte[] SECTION4 = " ] }\n".getBytes(ISO_8859_1);
    private static final byte[] ZERO = "00000000000000000000".getBytes(ISO_8859_1);
    private static final byte[] SEP = ", ".getBytes(ISO_8859_1);

    private static final int DIGITS = ZERO.length;
    private static final int CAPACITY = SECTION1.length;
    private static final int USED = CAPACITY + DIGITS + SECTION2.length;
    private static final int VALUES = USED + DIGITS + SECTION3.length;
    private static final int VALUE_SIZE = DIGITS + SEP.length;
    private static final int LOCK_OFFSET = 10;
    private static final int FALS = 'f' | ('a' << 8) | ('l' << 16) | ('s' << 24);
    private static final int TRU = ' ' | ('t' << 8) | ('r' << 16) | ('u' << 24);

    private long length = VALUES;

    /**
     * Write an initial state of an array with the given capacity to the provided bytes store.
     *
     * @param bytes    The byte store where the array state will be written.
     * @param capacity The capacity of the array.
     * @throws IllegalArgumentException  if the provided capacity is not valid.
     * @throws IllegalStateException     if the state of the byte store is invalid.
     * @throws BufferOverflowException   if writing exceeds the buffer's limit.
     * @throws ArithmeticException       if integer overflow occurs.
     * @throws BufferUnderflowException  if the buffer position is negative.
     */
    public static void write(@NotNull Bytes<?> bytes, @NonNegative long capacity)
            throws IllegalArgumentException, IllegalStateException, BufferOverflowException, ArithmeticException, BufferUnderflowException {
        long start = bytes.writePosition();
        bytes.write(SECTION1);
        bytes.append(capacity);
        while (bytes.writePosition() - start < CAPACITY + DIGITS) {
            bytes.writeUnsignedByte(' ');
        }
        bytes.write(SECTION2);
        bytes.write(ZERO);
        bytes.write(SECTION3);
        for (long i = 0; i < capacity; i++) {
            if (i > 0)
                bytes.appendUtf8(", ");
            bytes.write(ZERO);
        }
        bytes.write(SECTION4);
    }

    /**
     * Determines the length in bytes needed for an array based on the provided bytes store and offset.
     *
     * @param bytes  The byte store containing the array.
     * @param offset The offset of the array within the byte store.
     * @return The length in bytes.
     * @throws IllegalStateException     if the state of the byte store is invalid.
     * @throws BufferUnderflowException  if reading exceeds the buffer's limit.
     */
    public static long peakLength(@NotNull BytesStore bytes, @NonNegative long offset)
            throws IllegalStateException, BufferUnderflowException {
        //todo check this, I think there could be a bug here
        return (bytes.parseLong(offset + CAPACITY) * VALUE_SIZE) - SEP.length
                + VALUES + SECTION4.length;
    }

    /**
     * Get the number of elements that have been set in the array.
     *
     * @return The number of elements in use.
     * @throws IllegalStateException if the underlying bytes store is in an invalid state.
     */
    @Override
    public long getUsed()
            throws IllegalStateException {
        try {
            return bytes.parseLong(USED + offset);
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (BufferUnderflowException e) {
            throw new AssertionError(e);
        }
    }

    public void setUsed(long used)
            throws IllegalStateException {
        try {
            bytes.append(VALUES + offset, used, DIGITS);
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (IllegalArgumentException | BufferOverflowException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void setMaxUsed(long usedAtLeast)
            throws IllegalStateException {
        try {
            while (true) {
                if (!bytes.compareAndSwapInt(LOCK_OFFSET + offset, FALS, TRU))
                    continue;
                try {
                    if (getUsed() < usedAtLeast) {
                        setUsed(usedAtLeast);
                    }
                    return;
                } finally {
                    bytes.writeInt(LOCK_OFFSET + offset, FALS);
                }
            }
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (IllegalStateException | BufferOverflowException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public long getCapacity() {
        return (length - VALUES) / VALUE_SIZE;
    }

    @Override
    public ByteableLongArrayValues capacity(@NonNegative long arrayLength) {
        BytesStore bytesStore = bytesStore();
        long len = sizeInBytes(arrayLength);
        if (bytesStore == null) {
            this.length = len;
        } else {
            assert this.length == len;
        }
        return this;
    }

    @Override
    public long getValueAt(@NonNegative long index)
            throws IllegalStateException {
        try {
            return bytes.parseLong(VALUES + offset + index * VALUE_SIZE);
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (BufferUnderflowException e) {
            throw new AssertionError(e);
        }

    }

    @Override
    public void setValueAt(@NonNegative long index, long value)
            throws IllegalStateException {
        try {
            bytes.append(VALUES + offset + index * VALUE_SIZE, value, DIGITS);
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (IllegalArgumentException | BufferOverflowException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Binds an external {@link LongValue} to a specific index in this array.
     *
     * @param index The index at which the value should be bound.
     * @param value The LongValue to be bound.
     * @throws UnsupportedOperationException as this operation is not supported.
     */    @Override
    public void bindValueAt(@NonNegative long index, LongValue value) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public long getVolatileValueAt(@NonNegative long index)
            throws IllegalStateException {
        OS.memory().loadFence();
        return getValueAt(index);
    }

    @Override
    public void setOrderedValueAt(@NonNegative long index, long value)
            throws IllegalStateException {
        setValueAt(index, value);
        OS.memory().storeFence();
    }

    @Override
    public boolean compareAndSet(@NonNegative long index, long expected, long value)
            throws IllegalStateException {
        try {
            if (!bytes.compareAndSwapInt(LOCK_OFFSET + offset, FALS, TRU))
                return false;
            boolean ret = false;
            try {
                if (getVolatileValueAt(index) == expected) {
                    setOrderedValueAt(index, value);
                    ret = true;
                }
                return ret;
            } finally {
                bytes.writeInt(LOCK_OFFSET + offset, FALS);
            }
        } catch (NullPointerException e) {
            throwExceptionIfClosed();
            throw e;
        } catch (BufferOverflowException e) {
            throw new AssertionError(e);
        }

    }

    @Override
    public void bytesStore(final @NotNull BytesStore bytes, @NonNegative long offset, @NonNegative long length)
            throws IllegalStateException, BufferOverflowException, IllegalArgumentException {
        throwExceptionIfClosedInSetter();

        long peakLength = 0;
        try {
            peakLength = peakLength(bytes, offset);
        } catch (BufferUnderflowException e) {
            throw new DecoratedBufferOverflowException(e.toString());
        }
        if (length != peakLength)
            throw new IllegalArgumentException(length + " != " + peakLength);
        super.bytesStore(bytes, offset, length);
        this.length = length;
    }

    @Override
    public boolean isNull() {
        return bytes == null;
    }

    @Override
    public void reset()
            throws IllegalStateException {
        throwExceptionIfClosedInSetter();

        bytes = null;
        offset = 0;
        length = 0;
    }

    @Override
    public long maxSize() {
        return length;
    }

    /**
     * Returns a string representation of the TextLongArrayReference, for debugging purposes.
     *
     * @return String representing the state of TextLongArrayReference.
     */
    @NotNull
    @Override
    public String toString() {
        if (bytes == null) {
            return "LongArrayTextReference{" +
                    "bytes=null" +
                    ", offset=" + offset +
                    ", length=" + length +
                    '}';
        }

        try {
            return "value: " + getValueAt(0) + " ...";
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * Calculates the size in bytes needed for storing an array with the specified capacity.
     *
     * @param capacity The capacity of the array.
     * @return The size in bytes needed to store the array.
     */
    @Override
    public long sizeInBytes(@NonNegative long capacity) {
        return (capacity * VALUE_SIZE) + VALUES + SECTION3.length - SEP.length;
    }
}
