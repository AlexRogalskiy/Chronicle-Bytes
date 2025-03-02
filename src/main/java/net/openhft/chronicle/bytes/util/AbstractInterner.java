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
package net.openhft.chronicle.bytes.util;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.IOTools;
import org.jetbrains.annotations.NotNull;

import java.nio.BufferUnderflowException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * This class provides a caching mechanism that returns a value which matches the decoded bytes. It does not
 * guarantee the return of the same object across different invocations or from different threads, but it
 * guarantees that the contents will be the same. Although not strictly thread-safe, it behaves correctly
 * under concurrent access.
 * <p>
 * The main usage is to reduce the amount of memory used by creating new objects when the same byte sequence is
 * repeatedly decoded into an object.
 *
 * @author peter.lawrey
 * @param <T> the type of object to be interned
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class AbstractInterner<T> {
    protected final InternerEntry<T>[] entries;
    protected final int mask;
    protected final int shift;
    protected boolean toggle = false;

    /**
     * Constructor for creating an intern cache with the given capacity. The capacity will be adjusted to the next
     * power of 2 if it is not already a power of 2.
     *
     * @param capacity the desired capacity for the intern cache
     * @throws IllegalArgumentException if the calculated capacity exceeds the maximum possible array size
     */
    protected AbstractInterner(@NonNegative int capacity) {
        int n = Maths.nextPower2(capacity, 128);
        shift = Maths.intLog2(n);
        entries = new InternerEntry[n];
        mask = n - 1;
    }

    private static int hash32(@NotNull BytesStore bs, @NonNegative int length) throws IllegalStateException, BufferUnderflowException {
        return bs.fastHash(bs.readPosition(), length);
    }
    /**
     * Interns the specified Bytes object. If the Bytes object is already in the cache,
     * this method returns the cached instance; otherwise, it adds the Bytes object to the cache
     * and returns the newly cached instance. The length of Bytes object for interning is determined
     * by the remaining readable bytes.
     *
     * @param cs the Bytes object to intern
     * @return the interned instance
     * @throws IORuntimeException if an I/O error occurs
     * @throws BufferUnderflowException if there is not enough data in the buffer
     * @throws IllegalStateException if the buffer is in an unusable state
     */
    public T intern(@NotNull Bytes<?> cs)
            throws IORuntimeException, BufferUnderflowException, IllegalStateException {
        return intern((BytesStore) cs, (int) cs.readRemaining());
    }

    /**
     * Interns the specified BytesStore object. If the BytesStore object is already in the cache,
     * this method returns the cached instance; otherwise, it adds the BytesStore object to the cache
     * and returns the newly cached instance. The length of BytesStore object for interning is determined
     * by the remaining readable bytes.
     *
     * @param cs the BytesStore object to intern
     * @return the interned instance
     * @throws IORuntimeException if an I/O error occurs
     * @throws BufferUnderflowException if there is not enough data in the buffer
     * @throws IllegalStateException if the buffer is in an unusable state
     */
    public T intern(@NotNull BytesStore cs)
            throws IORuntimeException, BufferUnderflowException, IllegalStateException {
        return intern(cs, (int) cs.readRemaining());
    }

    /**
     * Interns the specified Bytes object of a given length. If the Bytes object is already in the cache,
     * this method returns the cached instance; otherwise, it adds the Bytes object to the cache
     * and returns the newly cached instance.
     *
     * @param cs the Bytes object to intern
     * @param length the length of the Bytes object to intern
     * @return the interned instance
     * @throws IORuntimeException if an I/O error occurs
     * @throws BufferUnderflowException if there is not enough data in the buffer
     * @throws IllegalStateException if the buffer is in an unusable state
     */
    public T intern(@NotNull Bytes<?> cs, @NonNegative int length)
            throws IORuntimeException, BufferUnderflowException, IllegalStateException {
        return intern((BytesStore) cs, length);
    }

    /**
     * Interns the specified Bytes. If the Bytes are already in the cache, this method returns the cached instance;
     * otherwise, it adds the Bytes to the cache and returns the newly cached instance.
     *
     * @param cs the Bytes to intern
     * @param length of bytes to read
     * @return the interned instance
     * @throws IORuntimeException if an I/O error occurs
     * @throws BufferUnderflowException if there is not enough data in the buffer
     * @throws IllegalStateException if the buffer is in an unusable state
     */
    public T intern(@NotNull BytesStore cs, @NonNegative int length)
            throws IORuntimeException, BufferUnderflowException, IllegalStateException {
        if (length > entries.length)
            return getValue(cs, length);
        // Todo: This needs to be reviewed: UnsafeMemory UNSAFE loadFence
        int hash = hash32(cs, length);
        int h = hash & mask;
        InternerEntry<T> s = entries[h];
        if (s != null && s.bytes.length() == length && s.bytes.equalBytes(cs, length))
            return s.t;
        int h2 = (hash >> shift) & mask;
        InternerEntry<T> s2 = entries[h2];
        if (s2 != null && s2.bytes.length() == length && s2.bytes.equalBytes(cs, length))
            return s2.t;
        @NotNull T t = getValue(cs, length);
        final byte[] bytes = new byte[length];
        @NotNull BytesStore bs = BytesStore.wrap(bytes);
        IOTools.unmonitor(bs);
        cs.read(cs.readPosition(), bytes, 0, length);
        entries[s == null || (s2 != null && toggle()) ? h : h2] = new InternerEntry<>(bs, t);
        // UnsafeMemory UNSAFE storeFence
        return t;
    }

    @NotNull
    protected abstract T getValue(BytesStore bs, @NonNegative int length)
            throws IORuntimeException, IllegalStateException, BufferUnderflowException;

    protected boolean toggle() {
        toggle = !toggle;
        return toggle;
    }

    /**
     * Returns the number of interned values in the cache.
     *
     * @return the number of interned values
     */
    public int valueCount() {
        return (int) Stream.of(entries).filter(Objects::nonNull).count();
    }

    private static final class InternerEntry<T> {
        final BytesStore bytes;
        final T t;

        InternerEntry(BytesStore bytes, T t) {
            this.bytes = bytes;
            this.t = t;
        }
    }
}
