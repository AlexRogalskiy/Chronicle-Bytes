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
package net.openhft.chronicle.bytes;

import net.openhft.chronicle.bytes.algo.OptimisedBytesStoreHash;
import net.openhft.chronicle.bytes.algo.VanillaBytesStoreHash;
import net.openhft.chronicle.bytes.internal.BytesInternal;
import net.openhft.chronicle.bytes.render.DecimalAppender;
import net.openhft.chronicle.bytes.render.GeneralDecimaliser;
import net.openhft.chronicle.bytes.render.MaximumPrecision;
import net.openhft.chronicle.bytes.render.StandardDecimaliser;
import net.openhft.chronicle.bytes.util.DecoratedBufferUnderflowException;
import net.openhft.chronicle.bytes.util.UTF8StringInterner;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.io.BackgroundResourceReleaser;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.Histogram;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Scanner;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.bytes.Allocator.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

@SuppressWarnings({"rawtypes"})
@RunWith(Parameterized.class)
public class BytesTest extends BytesTestCommon {

    private final Allocator alloc1;
    boolean parseDouble;

    public BytesTest(String ignored, Allocator alloc1) {
        this.alloc1 = alloc1;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"Native Unchecked", NATIVE_UNCHECKED},
                {"Native Wrapped", NATIVE},
                {"Native Address", NATIVE_ADDRESS},
                {"Heap", HEAP},
                {"Heap ByteBuffer", BYTE_BUFFER},
                {"Heap Unchecked", HEAP_UNCHECKED},
                {"Heap Embedded", HEAP_EMBEDDED},
                {"Hex Dump", HEX_DUMP}
        });
    }

    @Test
    public void readWriteLimit() {
        final Bytes<?> data = alloc1.elasticBytes(120);
        data.write8bit("Test me again");
        data.writeLimit(data.readLimit()); // this breaks the check
        assertEquals(data.read8bit(), "Test me again");
        data.releaseLast();
    }

    @Test
    public void emptyHash() {
        Bytes<?> bytes = alloc1.elasticBytes(2);
        try {
            final long actual1 = OptimisedBytesStoreHash.INSTANCE.applyAsLong(bytes);
            assertEquals(0, actual1);
            final long actual2 = VanillaBytesStoreHash.INSTANCE.applyAsLong(bytes);
            assertEquals(0, actual2);
        } finally {
            bytes.releaseLast();
        }
    }

    @Test
    public void testElastic2() {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<?> bytes = alloc1.elasticBytes(2);
        assumeTrue(bytes.isElastic());

        assertFalse(bytes.realCapacity() >= 1000);
        try {
            bytes.writePosition(1000);
            assertTrue(bytes.realCapacity() >= 1000);
            assertEquals(0L, bytes.readLong());
        } finally {
            bytes.releaseLast();
        }
    }

    @Test
    public void throwExceptionIfReleased() {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<?> bytes = alloc1.elasticBytes(16);
        ((AbstractReferenceCounted) bytes).throwExceptionIfReleased();
        postTest(bytes);
        try {
            ((AbstractReferenceCounted) bytes).throwExceptionIfReleased();
            fail();
        } catch (IllegalStateException ise) {
            // expected.
        }
    }

    @Test
    public void writeAdv() {
        Bytes<?> bytes = alloc1.fixedBytes(32);
        for (int i = 0; i < 4; i++)
            bytes.writeIntAdv('1', 1);
        assertEquals("1111", Bytes.toString(bytes));
        assertEquals("1111", Bytes.toString(bytes));
        postTest(bytes);
    }

    @Test
    public void writeLongAdv() {
        Bytes<?> bytes = alloc1.fixedBytes(32);
        for (int i = 0; i < 4; i++)
            bytes.writeLongAdv('1', 1);
        assertEquals("1111", bytes.toString());
        postTest(bytes);
    }

    @Test
    public void testName()
            throws IORuntimeException {
        Bytes<?> bytes = alloc1.fixedBytes(30);
        try {
            long expected = 12345L;
            int offset = 5;

            bytes.writeLong(offset, expected);
            bytes.writePosition(offset + 8);
            assertEquals(expected, bytes.readLong(offset));
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void readUnsignedByte() {
        Bytes<?> bytes = alloc1.fixedBytes(30);
        try {
            bytes.writeInt(0x11111111);
            bytes.readLimit(1);

            assertEquals(0x11, bytes.readUnsignedByte(0));
            assertEquals(-1, bytes.peekUnsignedByte(-1));
            assertEquals(-1, bytes.peekUnsignedByte(1));

            // as the offset is given it only needs to be under the writeLimit.
            assertEquals(0x11, bytes.readUnsignedByte(1));

        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void writeHistogram() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        @NotNull Bytes<?> bytes = alloc1.elasticBytes(0xFFFFF);
        @NotNull Histogram hist = new Histogram();
        hist.sample(10);
        @NotNull Histogram hist2 = new Histogram();
        for (int i = 0; i < 10000; i++)
            hist2.sample(i);

        bytes.writeHistogram(hist);
        bytes.writeHistogram(hist2);

        @NotNull Histogram histB = new Histogram();
        @NotNull Histogram histC = new Histogram();
        bytes.readHistogram(histB);
        bytes.readHistogram(histC);

        assertEquals(hist, histB);
        assertEquals(hist2, histC);
        postTest(bytes);
    }

    @Test
    public void testCopy() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        Bytes<ByteBuffer> bbb = (Bytes) alloc1.fixedBytes(1024);
        try {
            for (int i = 'a'; i <= 'z'; i++)
                bbb.writeUnsignedByte(i);
            bbb.readPositionRemaining(4, 12);
            BytesStore<Bytes<ByteBuffer>, ByteBuffer> copy = bbb.copy();
            bbb.writeUnsignedByte(10, '0');
            assertEquals("[pos: 0, rlim: 12, wlim: 12, cap: 12 ] efghijklmnop", copy.toDebugString());
            copy.releaseLast();
        } finally {
            postTest(bbb);
        }
    }

    @Test
    public void toHexString() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);
        assumeFalse(alloc1 == HEX_DUMP);

        Bytes<?> bytes = alloc1.elasticBytes(1020);
        try {
            bytes.append("Hello World");
            assertEquals("00000000 48 65 6c 6c 6f 20 57 6f  72 6c 64                Hello Wo rld     \n", bytes.toHexString());
            bytes.readLimit(bytes.realCapacity());
            assertEquals("00000000 48 65 6c 6c 6f 20 57 6f  72 6c 64 00 00 00 00 00 Hello Wo rld·····\n" +
                    "00000010 00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00 ········ ········\n" +
                    "........\n" +
                    "000003f0 00 00 00 00 00 00 00 00  00 00 00 00             ········ ····    \n", bytes.toHexString());

            assertEquals("00000000 48 65 6c 6c 6f 20 57 6f  72 6c 64 00 00 00 00 00 Hello Wo rld·····\n" +
                    "00000010 00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00 ········ ········\n" +
                    "........\n" +
                    "000000f0 00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00 ········ ········\n" +
                    "... truncated", bytes.toHexString(256));
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void fromHexString() {
        assumeFalse(NativeBytes.areNewGuarded());
        assumeFalse(alloc1 == HEAP_EMBEDDED);
        assumeFalse(alloc1 == HEX_DUMP);

        Bytes<?> bytes = alloc1.elasticBytes(260);
        try {
            for (int i = 0; i < 259; i++)
                bytes.writeByte((byte) i);
            @NotNull String s = bytes.toHexString();
            Bytes<?> bytes2 = Bytes.fromHexString(s);
            assertEquals(s, bytes2.toHexString());
            postTest(bytes2);
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void internRegressionTest()
            throws IORuntimeException {
        UTF8StringInterner utf8StringInterner = new UTF8StringInterner(4096);

        Bytes<?> bytes1 = alloc1.elasticBytes(64).append("TW-TRSY-20181217-NY572677_3256N1");
        Bytes<?> bytes2 = alloc1.elasticBytes(64).append("TW-TRSY-20181217-NY572677_3256N15");
        utf8StringInterner.intern(bytes1);
        String intern = utf8StringInterner.intern(bytes2);
        assertEquals(bytes2.toString(), intern);
        String intern2 = utf8StringInterner.intern(bytes1);
        assertEquals(bytes1.toString(), intern2);
        postTest(bytes1);
        postTest(bytes2);
    }

    @Test
    public void testEqualBytesWithSecondStoreBeingLonger()
            throws IORuntimeException {

        BytesStore store1 = null, store2 = null;
        try {
            store1 = alloc1.elasticBytes(64).append("TW-TRSY-20181217-NY572677_3256N1");
            store2 = alloc1.elasticBytes(64).append("TW-TRSY-20181217-NY572677_3256N15");
            assertFalse(store1.equalBytes(store2, store2.length()));
        } finally {
            store1.releaseLast();
            store2.releaseLast();
        }
    }

    @Test
    public void testStopBitDouble()
            throws IORuntimeException {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<?> b = alloc1.elasticBytes(1);
        try {
            testSBD(b, -0.0, "00000000 40                                               @         " +
                    "       \n");
            testSBD(b, -1.0, "00000000 DF 7C                                            ·|               \n");
            testSBD(b, -12345678, "00000000 E0 D9 F1 C2 4E                                   ····N            \n");
            testSBD(b, 0.0, "00000000 00                                               ·                \n");
            testSBD(b, 1.0, "00000000 9F 7C                                            ·|               \n");
            testSBD(b, 1024, "00000000 A0 24                                            ·$               \n");
            testSBD(b, 1000000, "00000000 A0 CB D0 48                                      ···H             \n");
            testSBD(b, 0.1, "00000000 9F EE B3 99 CC E6 B3 99  4D                      ········ M       \n");
            testSBD(b, Double.NaN, "00000000 BF 7E                                            ·~               \n");
        } finally {
            postTest(b);
        }
    }

    private void testSBD(@NotNull Bytes<?> b, double v, String s)
            throws IORuntimeException {
        b.clear();
        b.writeStopBit(v);
        assertEquals(s, b.toHexString().toUpperCase());
    }

    @Test
    public void testParseUtf8() {
        Bytes<?> bytes = alloc1.elasticBytes(1);
        try {
            assertEquals(1, bytes.refCount());
            bytes.appendUtf8("starting Hello World");
            @NotNull String s0 = bytes.parseUtf8(StopCharTesters.SPACE_STOP);
            assertEquals("starting", s0);
            @NotNull String s = bytes.parseUtf8(StopCharTesters.ALL);
            assertEquals("Hello World", s);
            assertEquals(1, bytes.refCount());
        } finally {
            postTest(bytes);
            assertEquals(0, bytes.refCount());
        }
    }

    @Test(expected = BufferOverflowException.class)
    public void testPartialWriteArray() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull byte[] array = "Hello World".getBytes(ISO_8859_1);
        Bytes<?> to = alloc1.fixedBytes(6);
        try {
            to.write(array);
        } finally {
            postTest(to);
        }
    }

    @Test
    public void testPartialWriteBB() {
        assumeFalse(alloc1 == HEX_DUMP);
        ByteBuffer bb = ByteBuffer.wrap("Hello World".getBytes(ISO_8859_1));
        Bytes<?> to = alloc1.fixedBytes(6);

        to.writeSome(bb);
        assertEquals("World", Bytes.wrapForRead(bb).toString());
        postTest(to);
    }

    @Test
    public void testCompact() {
        assumeFalse(alloc1 == HEX_DUMP);
        assumeFalse(NativeBytes.areNewGuarded());
        Bytes<?> from = alloc1.elasticBytes(1);
        try {
            from.write("Hello World");
            from.readLong();
            from.compact();
            assertEquals("rld", from.toString());
            assertEquals(0, from.readPosition());
        } finally {
            postTest(from);
        }
    }

    @Test
    public void testReadIncompleteLong()
            throws IllegalStateException, BufferOverflowException, BufferUnderflowException {
        assumeFalse(NativeBytes.areNewGuarded());
        Bytes<?> bytes = alloc1.elasticBytes(16);
        bytes.writeLong(0x0706050403020100L);
        bytes.writeLong(0x0F0E0D0C0B0A0908L);
        try {
            assertEquals(0x0706050403020100L, bytes.readIncompleteLong());
            assertEquals(0x0F0E0D0C0B0A0908L, bytes.readIncompleteLong());
            for (int i = 0; i <= 7; i++) {
                assertEquals("i: " + i, Long.toHexString(0x0B0A090807060504L >>> (i * 8)),
                        Long.toHexString(bytes.readPositionRemaining(4 + i, 8 - i)
                                .readIncompleteLong()));
            }
            assertEquals(0, bytes.readPositionRemaining(4, 0).readIncompleteLong());

        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void testUnwrite()
            throws IllegalArgumentException, BufferOverflowException, IllegalStateException, BufferUnderflowException {
        assumeFalse(alloc1 == HEX_DUMP);
        assumeFalse(NativeBytes.areNewGuarded());
        Bytes<?> bytes = alloc1.elasticBytes(1);
        try {
            for (int i = 0; i < 26; i++) {
                bytes.writeUnsignedByte('A' + i);
            }
            assertEquals(26, (int) bytes.writePosition());
            assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", bytes.toString());
            bytes.unwrite(1, 1);
            assertEquals(25, (int) bytes.writePosition());
            assertEquals("ACDEFGHIJKLMNOPQRSTUVWXYZ", bytes.toString());
        } finally {
            postTest(bytes);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExpectNegativeOffsetAbsoluteWriteOnElasticBytesThrowsIllegalArgumentException()
            throws BufferOverflowException, IllegalStateException {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<?> bytes = alloc1.elasticBytes(4);
        assumeFalse(bytes.unchecked());

        try {
            bytes.writeInt(-1, 1);
        } finally {
            postTest(bytes);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExpectNegativeOffsetAbsoluteWriteOnElasticBytesOfInsufficientCapacityThrowsIllegalArgumentException()
            throws IllegalStateException, BufferOverflowException {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<?> bytes = alloc1.elasticBytes(1);
        assumeFalse(bytes.unchecked());

        try {
            bytes.writeInt(-1, 1);
        } finally {
            postTest(bytes);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExpectNegativeOffsetAbsoluteWriteOnFixedBytesThrowsIllegalArgumentException() {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<ByteBuffer> bytes = (Bytes) alloc1.fixedBytes(4);
        try {
            bytes.writeInt(-1, 1);
        } finally {
            postTest(bytes);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExpectNegativeOffsetAbsoluteWriteOnFixedBytesOfInsufficientCapacityThrowsIllegalArgumentException() {
        assumeFalse(alloc1 == HEX_DUMP);
        Bytes<ByteBuffer> bytes = (Bytes) alloc1.fixedBytes(1);
        try {
            bytes.writeInt(-1, 1);
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void testWriter()
            throws IllegalStateException {
        assumeFalse(NativeBytes.areNewGuarded());
        Bytes<?> bytes = alloc1.elasticBytes(1);
        @NotNull PrintWriter writer = new PrintWriter(bytes.writer());
        writer.println(1);
        writer.println("Hello");
        writer.println(12.34);
        writer.append('a').append('\n');
        writer.append("bye\n");
        writer.append("for now\nxxxx", 0, 8);
        assertEquals("1\n" +
                "Hello\n" +
                "12.34\n" +
                "a\n" +
                "bye\n" +
                "for now\n", bytes.toString().replaceAll("\r\n", "\n"));
        try (@NotNull Scanner scan = new Scanner(bytes.reader())) {
            scan.useLocale(Locale.ENGLISH);
            assertEquals(1, scan.nextInt());
            assertEquals("", scan.nextLine());
            assertEquals("Hello", scan.nextLine());
            assertEquals(12.34, scan.nextDouble(), 0.0);
            assertEquals("", scan.nextLine());
            assertEquals("a", scan.nextLine());
            assertEquals("bye", scan.nextLine());
            assertEquals("for now", scan.nextLine());
            assertFalse(scan.hasNext());
            postTest(bytes);
        }
    }

    @Test
    public void testParseUtf8High()
            throws BufferUnderflowException, BufferOverflowException, IllegalStateException {

        @NotNull Bytes<?> b = alloc1.elasticBytes(4);
        for (int i = ' '; i <= Character.MAX_VALUE; i++) {
            if (!Character.isValidCodePoint(i))
                continue;

            b.clear();
            b.appendUtf8(i);
            b.appendUtf8("\r\n");
            @NotNull StringBuilder sb = new StringBuilder();
            b.parseUtf8(sb, StopCharTesters.CONTROL_STOP);
            assertEquals(Character.toString((char) i), sb.toString());
            sb.setLength(0);
            b.readPosition(0);
            b.parseUtf8(sb, (ch, nextCh) -> ch < ' ' && nextCh < ' ');
            assertEquals(Character.toString((char) i), sb.toString());
        }
        postTest(b);
    }

    @Test
    public void testBigDecimalBinary()
            throws BufferUnderflowException, ArithmeticException {
        for (double d : new double[]{1.0, 1000.0, 0.1}) {
            @NotNull Bytes<?> b = alloc1.elasticBytes(16);
            b.writeBigDecimal(new BigDecimal(d));

            @NotNull BigDecimal bd = b.readBigDecimal();
            assertEquals(new BigDecimal(d), bd);
            postTest(b);
        }
    }

    @Test
    public void testBigDecimalText() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);
        for (double d : new double[]{1.0, 1000.0, 0.1}) {
            @NotNull Bytes<?> b = alloc1.elasticBytes(0xFFFF);
            b.append(new BigDecimal(d));

            @NotNull BigDecimal bd = b.parseBigDecimal();
            assertEquals(new BigDecimal(d), bd);
            postTest(b);
        }
    }

    @Test
    public void testWithLength() {
        assumeFalse(NativeBytes.areNewGuarded());
        Bytes<?> hello = Bytes.from("hello");
        Bytes<?> world = Bytes.from("world");
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        b.writeWithLength(hello);
        b.writeWithLength(world);
        assertEquals("hello", hello.toString());

        @NotNull Bytes<?> b2 = alloc1.elasticBytes(16);
        b.readWithLength(b2);
        assertEquals("hello", b2.toString());
        b.readWithLength(b2);
        assertEquals("world", b2.toString());

        postTest(b);
        postTest(b2);
        postTest(hello);
        postTest(world);
    }

    @Test
    public void testAppendBase() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        for (long value : new long[]{Long.MIN_VALUE, Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            for (int base : new int[]{10, 16}) {
                String s = Long.toString(value, base);
                b.clear().appendBase(value, base);
                assertEquals(s, b.toString());
            }
        }
        postTest(b);
    }

    @Test
    public void testAppendBase16() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        for (long value : new long[]{Long.MIN_VALUE, Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            String s = Long.toHexString(value).toLowerCase();
            b.clear().appendBase16(value);
            assertEquals(s, b.toString());
        }
        postTest(b);
    }

    @Test
    public void testMove() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.append("Hello World");
            b.move(3, 1, 3);
            assertEquals("Hlo o World", b.toString());
            b.move(3, 5, 3);
            assertEquals("Hlo o o rld", b.toString());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void testMove2() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);

        b.append("0123456789");
        b.move(3, 1, 3);
        assertEquals("0345456789", b.toString());
        postTest(b);
        assertThrows(IllegalStateException.class, () ->
                b.move(3, 5, 3)
        );
    }

    @Test
    public void testMoveForward() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);

        b.append("0123456789abcdefg");
        b.move(1, 3, 10);
        assertEquals("012123456789adefg", b.toString());
        postTest(b);
    }

    @Test
    public void testMoveBackward() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);

        b.append("0123456789abcdefg");
        b.move(3, 1, 10);
        assertEquals("03456789abcbcdefg", b.toString());
        postTest(b);
    }

    @Test
    public void testMove2B() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);

        b.append("Hello World");
        b.bytesStore().move(3, 1, 3);
        assertEquals("Hlo o World", b.toString());
        postTest(b);
        BackgroundResourceReleaser.releasePendingResources();
        final BytesStore<?, ?> bs = b.bytesStore();
        assertNotNull(bs);
        assertThrows(IllegalStateException.class, () ->
                bs.move(3, 5, 3)
        );
    }

    @Test
    public void testReadPosition() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.readPosition(17);
            assertTrue(b.unchecked());
        } catch (DecoratedBufferUnderflowException ex) {
            assertFalse(b.unchecked());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void testReadPositionTooSmall() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.readPosition(-1);
            assertTrue(b.unchecked());
        } catch (DecoratedBufferUnderflowException ex) {
            assertFalse(b.unchecked());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void testReadLimit() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.readPosition(b.writeLimit() + 1);
            assertTrue(b.unchecked());
        } catch (DecoratedBufferUnderflowException ex) {
            assertFalse(b.unchecked());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void testReadLimitTooSmall() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.readPosition(b.start() - 1);
            assertTrue(b.unchecked());
        } catch (DecoratedBufferUnderflowException ex) {
            assertFalse(b.unchecked());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void uncheckedSkip() {
        assumeFalse(NativeBytes.areNewGuarded());

        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.uncheckedReadSkipOne();
            assertEquals(1, b.readPosition());
            b.uncheckedReadSkipBackOne();
            assertEquals(0, b.readPosition());
            b.writeUnsignedByte('H');
            b.writeUnsignedByte(0xFF);
            assertEquals('H', b.uncheckedReadUnsignedByte());
            assertEquals(0xFF, b.uncheckedReadUnsignedByte());
        } finally {
            postTest(b);
        }
    }

    @Test
    public void readVolatile() {
        assumeFalse(alloc1 == HEX_DUMP);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.writeVolatileByte(0, (byte) 1);
            b.writeVolatileShort(1, (short) 2);
            b.writeVolatileInt(3, 3);
            b.writeVolatileLong(7, 4);
            assertEquals(1, b.readVolatileByte(0));
            assertEquals(2, b.readVolatileShort(1));
            assertEquals(3, b.readVolatileInt(3));
            assertEquals(4, b.readVolatileLong(7));

        } finally {
            postTest(b);
        }
    }

    @Test
    public void testHashCode() {
        assumeFalse(NativeBytes.areNewGuarded());

        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.writeLong(0);
            assertEquals(0, b.hashCode());
            b.clear();
            b.writeLong(1);
            assertEquals(0x152ad77e, b.hashCode());
            b.clear();
            b.writeLong(2);
            assertEquals(0x2a55aefc, b.hashCode());
            b.clear();
            b.writeLong(3);
            assertEquals(0x7f448df2, b.hashCode());
            b.clear();
            b.writeLong(4);
            assertEquals(0x54ab5df8, b.hashCode());

        } finally {
            postTest(b);
        }
    }

    @Test
    public void testEnum() {

        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.writeEnum(HEAP);
            b.writeEnum(NATIVE);
            assertEquals(HEAP, b.readEnum(Allocator.class));
            assertEquals(NATIVE, b.readEnum(Allocator.class));

        } finally {
            postTest(b);
        }
    }

    @Test
    public void testTimeMillis() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.appendTimeMillis(12345678L);
            assertEquals("03:25:45.678", b.toString());

        } finally {
            postTest(b);
        }
    }

    @Test
    public void testDateTimeMillis() {
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            b.appendDateMillis(12345 * 86400_000L);
            assertEquals("20031020", b.toString());

        } finally {
            postTest(b);
        }
    }

    @Test
    public void testWriteOffset() {
        int length = 127;
        Bytes<?> from = NativeBytes.nativeBytes(length).unchecked(true);
        Bytes<?> to = alloc1.elasticBytes(length);

        Bytes<?> a = Bytes.from("a");
        for (int i = 0; i < length; i++) {
            from.write(i, a, 0L, 1);
        }
        postTest(a);

        try {
            to.write(from, 0L, length);
            assertEquals(from.readLong(0), to.readLong(0));
        } finally {
            postTest(from);
            postTest(to);
        }
    }

    @Test
    public void testToStringDoesNotChange() {
        @NotNull Bytes<?> a = alloc1.elasticBytes(16);
        @NotNull Bytes<?> b = alloc1.elasticBytes(16);
        try {
            String hello = "hello";
            a.append(hello);
            b.append(hello);

            assertTrue(a.contentEquals(b));
            assertEquals(a.bytesStore(), b.bytesStore());

            assertEquals(hello, b.toString());

            assertTrue(a.contentEquals(b));
            assertEquals(a.bytesStore(), b.bytesStore());
        } finally {
            postTest(a);
            postTest(b);
        }
    }

    @Test
    public void to8BitString() {
        @NotNull Bytes<?> a = alloc1.elasticBytes(16);
        try {
            assertEquals(a.toString(), a.to8bitString());
            String hello = "hello";
            a.append(hello);
            assertEquals(a.toString(), a.to8bitString());
        } finally {
            postTest(a);
        }
    }

    @Test
    public void testParseDoubleReadLimit() {
        Bytes<ByteBuffer> bytes = (Bytes) alloc1.fixedBytes(52);
        try {
            final String spaces = "   ";
            bytes.append(spaces).append(1.23);
            bytes.readLimit(spaces.length());
            // only fails when assertions are off
            assertEquals(0, BytesInternal.parseDouble(bytes), 0);
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void write8BitString() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        @NotNull Bytes<?> bytes = alloc1.elasticBytes(703);
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i <= 36; i++) {
                final String s = sb.toString();
                bytes.write8bit(s);
                String s2 = bytes.read8bit();
                assertEquals(s, s2);
                sb.append(Integer.toString(i, 36));
            }
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void write8BitNativeBytes() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        @NotNull Bytes<?> bytes = alloc1.elasticBytes(703);
        Bytes<?> nbytes = Bytes.allocateDirect(36);
        Bytes<?> nbytes2 = Bytes.allocateDirect(36);
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i <= 36; i++) {
                nbytes.clear().append(sb);
                if (nbytes == null) {
                    bytes.writeStopBit(-1);
                } else {
                    long offset = nbytes.readPosition();
                    long readRemaining = Math.min(bytes.writeRemaining(), nbytes.readLimit() - offset);
                    bytes.writeStopBit(readRemaining);
                    try {
                        bytes.write(nbytes, offset, readRemaining);
                    } catch (BufferUnderflowException | IllegalArgumentException e) {
                        throw new AssertionError(e);
                    }
                }
                bytes.read8bit(nbytes2.clear());

                final String s = sb.toString();
                assertEquals(s, nbytes2.toString());
                sb.append(Integer.toString(i, 36));
            }
        } finally {
            postTest(bytes);
            postTest(nbytes);
            postTest(nbytes2);
        }
    }

    @Test
    public void write8BitHeapBytes() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        @NotNull Bytes<?> bytes = alloc1.elasticBytes(703);
        Bytes<?> nbytes = Bytes.allocateElasticOnHeap(36);
        Bytes<?> nbytes2 = Bytes.allocateElasticOnHeap(36);
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i <= 36; i++) {
                nbytes.clear().append(sb);
                if (nbytes == null) {
                    bytes.writeStopBit(-1);
                } else {
                    long offset = nbytes.readPosition();
                    long readRemaining = Math.min(bytes.writeRemaining(), nbytes.readLimit() - offset);
                    bytes.writeStopBit(readRemaining);
                    try {
                        bytes.write(nbytes, offset, readRemaining);
                    } catch (BufferUnderflowException | IllegalArgumentException e) {
                        throw new AssertionError(e);
                    }
                }
                bytes.read8bit(nbytes2.clear());

                assertEquals(sb.toString(), nbytes2.toString());
                sb.append(Integer.toString(i, 36));
            }
        } finally {
            postTest(bytes);
            postTest(nbytes);
            postTest(nbytes2);
        }
    }

    @Test
    public void write8BitCharSequence() {
        assumeFalse(alloc1 == HEAP_EMBEDDED);

        @NotNull Bytes<?> bytes = alloc1.elasticBytes(703);
        StringBuilder sb = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        try {
            for (int i = 0; i <= 36; i++) {
                bytes.write8bit(sb);
                bytes.read8bit(sb2);

                assertEquals(sb.toString(), sb2.toString());
                sb.append(Integer.toString(i, 36));
            }
        } finally {
            postTest(bytes);
        }
    }

    @Test
    public void stopBitChar() {
        final Bytes<?> bytes = alloc1.fixedBytes(64);
        for (int i = Character.MIN_VALUE; i <= Character.MAX_VALUE; i++) {
            bytes.clear();
            char ch = (char) i;
            bytes.writeStopBit(ch);
            bytes.writeUnsignedByte(0x80);
            char c2 = bytes.readStopBitChar();
            assertEquals(ch, c2);
            assertEquals(0x80, bytes.readUnsignedByte());
        }
        postTest(bytes);
    }

    @Test
    public void stopBitLong() {
        final Bytes<?> bytes = alloc1.fixedBytes(64);
        for (int i = 0; i <= 63; i++) {
            long l = 1L << i;
            stopBitLong0(bytes, l);
            stopBitLong0(bytes, l - 1);
            stopBitLong0(bytes, -l);
            stopBitLong0(bytes, ~l);
        }
        postTest(bytes);
    }

    @Test
    public void stopBitNeg1() {
        final Bytes<?> bytes = alloc1.fixedBytes(64);
        BytesInternal.writeStopBitNeg1(bytes);
        BytesInternal.writeStopBitNeg1(bytes);
        assertEquals(-1, bytes.readStopBit());
        assertEquals(0xFFFF, bytes.readStopBitChar());
        postTest(bytes);
    }

    private void postTest(Bytes<?> bytes) {
        bytes.clear();
        assertTrue(bytes.isClear());
        assertEquals(0, bytes.readRemaining());
        bytes.releaseLast();
    }

    private void stopBitLong0(Bytes<?> bytes, long l) {
        bytes.clear();
        bytes.writeStopBit(l);
        bytes.writeUnsignedByte(0x80);
        long l2 = bytes.readStopBit();
        assertEquals(l, l2);
        assertEquals(0x80, bytes.readUnsignedByte());
    }

    @Test
    public void capacityVsWriteLimitInvariant() {
        final Bytes<?> bytes = alloc1.elasticBytes(20);
        assumeTrue(bytes.isElastic());
        assertEquals(bytes.capacity(), bytes.writeLimit());
    }

    @Test
    public void isClear() {
        final Bytes<?> bytes = alloc1.elasticBytes(20);
        assertTrue(bytes.isClear());
        bytes.releaseLast();
    }

    @Test
    public void testAppendDoubleWithoutParseDouble() {

        parseDouble = false;
        // TODO FIX parseDouble()
        testAppendDoubleOnce(1e-11 + Math.ulp(1e-11), "0.00000000001", "0.00000000001", "0.000000000010000000000000001", "0");
        testAppendDoubleOnce(1.0626477603237785E-10, "0.000000000106264776", "0.000000000106264775", "0.00000000010626477603237785", "0");
        testAppendDoubleOnce(1e-18 - Math.ulp(1e-18), "0.000000000000000001", "0.000000000000000001", "0.0000000000000000009999999999999999", "0");
        testAppendDoubleOnce(1e45, "1000000000000000000000000000000000000000000000", "Infinity", "1.0E45", "");
        testAppendDoubleOnce(1e45 + Math.ulp(1e45), "1000000000000000100000000000000000000000000000", "Infinity", "1.0000000000000001E45", "");
        testAppendDoubleOnce(-Float.MAX_VALUE, "-340282346638528860000000000000000000000", "-340282350000000000000000000000000000000", "-340282346638528860000000000000000000000", "");
        testAppendDoubleOnce(-Double.MIN_NORMAL, "-0", "-0", "-2.2250738585072014E-308", "-0");
    }

    @Test
    public void testAppendDoubleRandom() {

        parseDouble = true;

        // ok
        testAppendDoubleOnce(-145344868913.80002, "-145344868913.80002", "-145344872448", "-145344868913.80002", "-145344868913.80002");

        testAppendDoubleOnce(-1.4778838950354771E-9, "-0.000000001477883895", "-0.0000000014778839", "-0.0000000014778838950354771", "-0.000000001");
        testAppendDoubleOnce(1.4753448053710411E-8, "0.000000014753448054", "0.000000014753448", "0.000000014753448053710411", "0.000000015");
        testAppendDoubleOnce(4.731428525883379E-10, "0.000000000473142853", "0.00000000047314286", "0.0000000004731428525883379", "0");
        testAppendDoubleOnce(1.0E-5, "0.00001", "0.00001", "0.00001", "0.00001");
        testAppendDoubleOnce(5.7270847085938394E-9, "0.000000005727084709", "0.0000000057270846", "0.0000000057270847085938394", "0.000000006");
        testAppendDoubleOnce(-3.5627763205104632E-9, "-0.000000003562776321", "-0.0000000035627763", "-0.0000000035627763205104632", "-0.000000004");
        testAppendDoubleOnce(3.4363211797092447E-10, "0.000000000343632118", "0.00000000034363212", "0.00000000034363211797092447", "0");
        testAppendDoubleOnce(0.7205789375929972, "0.7205789375929972", "0.7205789", "0.7205789375929972", "0.720578938");
        testAppendDoubleOnce(1.7205789375929972E-8, "0.000000017205789376", "0.000000017205789", "0.000000017205789375929972", "0.000000017");
        testAppendDoubleOnce(1.000000459754255, "1.000000459754255", "1.0000005", "1.000000459754255", "1.00000046");
        testAppendDoubleOnce(1.0000004597542551, "1.0000004597542552", "1.0000005", "1.0000004597542552", "1.00000046");
        testAppendDoubleOnce(-0.0042633243189823394, "-0.00426332431898234", "-0.004263324", "-0.0042633243189823394", "-0.004263324");
        testAppendDoubleOnce(4.3634067645459027E-4, "0.00043634067645459", "0.00043634066", "0.00043634067645459027", "0.000436341");
        testAppendDoubleOnce(-4.8378951079402273E-4, "-0.000483789510794023", "-0.0004837895", "-0.00048378951079402273", "-0.00048379");
        testAppendDoubleOnce(3.8098893793449994E-4, "0.0003809889379345", "0.00038098893", "0.00038098893793449994", "0.000380989");
        testAppendDoubleOnce(-0.0036980489197619678, "-0.003698048919761968", "-0.0036980489", "-0.0036980489197619678", "-0.003698049");
        testAppendDoubleOnce(1.1777536373898703E-7, "0.000000117775363739", "0.000000117775365", "0.00000011777536373898703", "0.000000118");
        testAppendDoubleOnce(8.577881719106565E-8, "0.000000085778817191", "0.000000085778815", "0.00000008577881719106565", "0.000000086");
        testAppendDoubleOnce(1.1709707236415293E-7, "0.000000117097072364", "0.00000011709707", "0.00000011709707236415293", "0.000000117");
        testAppendDoubleOnce(1.0272238286878982E-7, "0.000000102722382869", "0.00000010272238", "0.00000010272238286878982", "0.000000103");
        testAppendDoubleOnce(9.077547054210796E-8, "0.000000090775470542", "0.00000009077547", "0.00000009077547054210796", "0.000000091");
        testAppendDoubleOnce(-1.1914407211387385E-7, "-0.000000119144072114", "-0.00000011914407", "-0.00000011914407211387385", "-0.000000119");
        testAppendDoubleOnce(8.871684275243539E-4, "0.000887168427524354", "0.00088716845", "0.0008871684275243539", "0.000887168");
        testAppendDoubleOnce(8.807878708605213E-4, "0.000880787870860521", "0.00088078785", "0.0008807878708605213", "0.000880788");
        testAppendDoubleOnce(8.417670165790972E-4, "0.000841767016579097", "0.000841767", "0.0008417670165790972", "0.000841767");
        testAppendDoubleOnce(0.0013292726996348332, "0.001329272699634833", "0.0013292728", "0.0013292726996348332", "0.001329273");
        testAppendDoubleOnce(2.4192540417349368E-4, "0.000241925404173494", "0.0002419254", "0.00024192540417349368", "0.000241925");
        testAppendDoubleOnce(1.9283711356548258E-4, "0.000192837113565483", "0.00019283712", "0.00019283711356548258", "0.000192837");
        testAppendDoubleOnce(-8.299137873077923E-5, "-0.000082991378730779", "-0.00008299138", "-0.00008299137873077923", "-0.000082991");
    }

    @Test
    public void testAppendDoublePowersOfTen() {
        parseDouble = true;
        // OK
        testAppendDoubleOnce(0.0, "0", "0", "0", "0");
        testAppendDoubleOnce(0.001, "0.001", "0.001", "0.001", "0.001");
        testAppendDoubleOnce(1.0E-4, "0.0001", "0.0001", "0.0001", "0.0001");
        testAppendDoubleOnce(1.0E-6, "0.000001", "0.000001", "0.000001", "0.000001");
        testAppendDoubleOnce(1.0E-7, "0.0000001", "0.0000001", "0.0000001", "0.0000001");
        testAppendDoubleOnce(1.0E-8, "0.00000001", "0.00000001", "0.00000001", "0.00000001");
        testAppendDoubleOnce(1.0E-9, "0.000000001", "0.000000001", "0.000000001", "0.000000001");
        testAppendDoubleOnce(0.009, "0.009", "0.009", "0.009", "0.009");
        testAppendDoubleOnce(9.0E-4, "0.0009", "0.0009", "0.0009", "0.0009");
        testAppendDoubleOnce(9.0E-5, "0.00009", "0.00009", "0.00009", "0.00009");
        testAppendDoubleOnce(9.0E-6, "0.000009", "0.000009", "0.000009", "0.000009");
        testAppendDoubleOnce(9.0E-7, "0.0000009", "0.0000009", "0.0000009", "0.0000009");
        testAppendDoubleOnce(9.0E-8, "0.00000009", "0.00000009", "0.00000009", "0.00000009");
        testAppendDoubleOnce(9.0E-9, "0.000000009", "0.000000009", "0.000000009", "0.000000009");
    }

    @Test
    public void testAppendDoubleEdgeCases() {
        parseDouble = true;
        testAppendDoubleOnce(Double.NaN, "NaN", "NaN", "NaN", "");
        testAppendDoubleOnce(Double.POSITIVE_INFINITY, "Infinity", "Infinity", "Infinity", "");
        testAppendDoubleOnce(Double.NEGATIVE_INFINITY, "-Infinity", "-Infinity", "-Infinity", "");
        testAppendDoubleOnce(0.1, "0.1", "0.1", "0.1", "0.1");
        testAppendDoubleOnce(12.0, "12", "12", "12", "12");
        testAppendDoubleOnce(12.1, "12.1", "12.1", "12.1", "12.1");
        testAppendDoubleOnce(12.00000001, "12.00000001", "12", "12.00000001", "12.00000001");

        testAppendDoubleOnce(1e-6 + Math.ulp(1e-6), "0.000001", "0.000001", "0.0000010000000000000002", "0.000001");
        testAppendDoubleOnce(1e-7 + Math.ulp(1e-7), "0.0000001", "0.0000001", "0.00000010000000000000001", "0.0000001");
        testAppendDoubleOnce(1e-8 + Math.ulp(1e-8), "0.00000001", "0.00000001", "0.000000010000000000000002", "0.00000001");
        testAppendDoubleOnce(1e-9 + Math.ulp(1e-9), "0.000000001", "0.000000001", "0.0000000010000000000000003", "0.000000001");
        testAppendDoubleOnce(1e-10 + Math.ulp(1e-10), "0.0000000001", "0.0000000001", "0.00000000010000000000000002", "0");
        testAppendDoubleOnce(1e-12 + Math.ulp(1e-12), "0.000000000001", "0.000000000001", "0.0000000000010000000000000002", "0");
        testAppendDoubleOnce(1.0626477603237786E-11, "0.000000000010626478", "0.000000000010626478", "0.000000000010626477603237786", "0");
    }

    @Test
    public void testAppendDoubleLimits() {
        // limits
        testAppendDoubleOnce(1.0E-18, "0.000000000000000001", "0.000000000000000001", "0.000000000000000001", "0");
        testAppendDoubleOnce(1.0E-29, "0", "0", "0.000000000000000000000000000010", "0");
        testAppendDoubleOnce(1e-29 - Math.ulp(1e-29), "0", "0", "9.999999999999998E-30", "0");
        testAppendDoubleOnce(1e45 - Math.ulp(1e45), "999999999999999800000000000000000000000000000", "Infinity", "999999999999999800000000000000000000000000000", "");
        testAppendDoubleOnce(-Double.MIN_VALUE, "-0", "-0", "-4.9E-324", "-0");
        testAppendDoubleOnce(-Float.MIN_VALUE, "-0", "-0", "-1.401298464324817E-45", "-0");

        testAppendDoubleOnce(0.0, "0.0", "0.0", "0.0", "0.0", true);
        testAppendDoubleOnce(-Double.MIN_VALUE, "-0.0", "-0.0", "-4.9E-324", "-0.0", true);
        testAppendDoubleOnce(-Float.MIN_VALUE, "-0.0", "-0.0", "-1.401298464324817E-45", "-0.0", true);
        testAppendDoubleOnce(12.0, "12.0", "12.0", "12.0", "12.0", true);
        testAppendDoubleOnce(Long.MIN_VALUE, "-9223372036854776000.0", "-9223372000000000000.0", "-9223372036854775807.0", "", true);
        testAppendDoubleOnce(Long.MAX_VALUE, "9223372036854776000.0", "9223372000000000000.0", "9223372036854775807.0", "", true);

        assumeFalse(alloc1 == HEAP_EMBEDDED || alloc1 == HEAP_UNCHECKED);
        testAppendDoubleOnce(-Double.MAX_VALUE, "-179769313486231570000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", "-Infinity", "-1.7976931348623157E308", "");
    }

    @Test
    public void testAppendReallySmallDouble() {
        assumeFalse(alloc1 == HEAP_UNCHECKED);
        int size = 48;
        Bytes<?> bytes = alloc1.elasticBytes(size + 8);
        bytes.decimaliser(GeneralDecimaliser.GENERAL);

        for (double d = 1; d >= Double.MIN_NORMAL; d *= 0.99) {
            bytes.writeLong(size, 0);
            bytes.clear();
            bytes.append(d);
            assertEquals("d: " + d, 0, bytes.readLong(size));
            // Determine expected precision error based on magnitude of value
            // ok for not easily decimalised
            double err = d > 2.3e-10 ? 0
                    : d > 2.0e-13 && !Jvm.isArm() ? Math.ulp(d)
                    : 2 * Math.ulp(d);
            assertEquals(d, bytes.parseDouble(), err);
        }
        bytes.releaseLast();
    }

    @Test
    public void testAppendReallyBigDouble() {
        assumeFalse(alloc1 == HEAP_UNCHECKED);
        int size = 48;
        Bytes<?> bytes = alloc1.elasticBytes(size + 8);
        bytes.decimaliser(GeneralDecimaliser.GENERAL);

        for (double d = -1; d > Double.NEGATIVE_INFINITY; d *= 1.01) {
            bytes.writeLong(size, 0);
            bytes.clear();
            bytes.append(d);
            assertEquals("d: " + d, 0, bytes.readLong(size));
            // Determine expected precision error based on magnitude of value
            // ok for not easily decimalised
            double err = d > -1.3e12 ? 0
                    : d > -1e39 ? Math.ulp(d)
                    : 2 * Math.ulp(d);
            double actual = bytes.parseDouble();
            assertEquals(d, actual, err);
        }
        bytes.releaseLast();
    }

    @Test
    public void testAppendReallySmallFloat() {
        assumeFalse(alloc1 == HEAP_UNCHECKED);
        int size = 48;
        Bytes<?> bytes = alloc1.elasticBytes(size + 8);
        bytes.decimaliser(GeneralDecimaliser.GENERAL);

        for (float f = 1; f > Float.MIN_NORMAL; f *= 0.99f) {
            bytes.writeLong(size, 0);
            bytes.clear();
            bytes.append(f);
            assertEquals("f: " + f, 0, bytes.readLong(size));
            // Determine expected precision error based on magnitude of value
            // ok for not easily decimalised
            float err = f > 1.2e-4 ? 0 : Math.ulp(f);
            assertEquals(f, bytes.parseFloat(), err);
        }
        bytes.releaseLast();
    }


    @Test
    public void testAppendReallyBigFloat() {
        int size = 48;
        Bytes<?> bytes = alloc1.elasticBytes(size + 8);

        for (float f = 1; f < Float.POSITIVE_INFINITY; f *= 1.01f) {
            bytes.writeLong(size, 0);
            bytes.clear();
            bytes.append(f);
            assertEquals("f: " + f, 0, bytes.readLong(size));
            assertEquals(f, bytes.parseFloat(), 0.0f);
        }
        bytes.releaseLast();
    }

    @Test
    public void testReadWithOffset() {
        Bytes<?> bytes = alloc1.elasticBytes(32);
        bytes.append("Hello");
        int offset = 2;
        int offsetInRDI = 1;
        byte[] ba = new byte[bytes.length() + offset - offsetInRDI];
        ba[0] = '0';
        ba[1] = '1';
        bytes.read(offsetInRDI, ba, offset, bytes.length() - offsetInRDI);
        assertEquals("01ello", new String(ba));
        bytes.releaseLast();
    }

    @Test
    public void writeSkipNegative() {
        @NotNull Bytes<?> a = alloc1.elasticBytes(16);
        try {
            String hello = "hello";
            a.append(hello);
            assertEquals(hello, a.toString());
            a.writeSkip(-hello.length());
            assertEquals("", a.toString());
            if (!a.unchecked())
                assertThrows(BufferOverflowException.class, () -> a.writeSkip(-1));
        } finally {
            postTest(a);
        }
    }

    @Test
    public void testCopyToStream() throws IOException {
        @NotNull Bytes<?> a = alloc1.elasticBytes(16);
        String text = "Hello World";

        try {
            a.append(text);
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                a.copyTo(os);

                byte[] array = os.toByteArray();
                assertEquals(text.length(), array.length);
                assertArrayEquals(text.getBytes("UTF8"), array);
            }
        } finally {
            postTest(a);
        }
    }

    private void testAppendDoubleOnce(double value, String standard, String standardFloat, String general, String expectedDecimal9) {
        testAppendDoubleOnce(value, standard, standardFloat, general, expectedDecimal9, false);
    }

    private void testAppendDoubleOnce(double value, String standard, String standardFloat, String general, String expectedDecimal9, boolean append0) {
        @NotNull Bytes<?> a = alloc1.elasticBytes(255)
                .fpAppend0(append0)
                .decimaliser(StandardDecimaliser.STANDARD);
        try {
            a.append(value);
            String actual = a.toString();
            assertEquals(standard, actual);

            a.clear();
            a.append((float) value);
            String actual2 = a.toString();
            assertEquals(standardFloat, actual2);

            a.decimaliser(GeneralDecimaliser.GENERAL);
            a.clear();
            a.append(value);
            String actualg = a.toString();
            double actualParsed = a.parseDouble();
            if (parseDouble)
                assertEquals("parseDouble", value, actualParsed, 0.0);
            assertEquals(general, actualg);

            a.clear();
            // if empty don't expect it to be translated
            boolean decimal = new MaximumPrecision(9).toDecimal(value, (DecimalAppender) a);
            assertEquals(!expectedDecimal9.isEmpty(), decimal);
            String actual3 = a.toString();
            assertEquals(expectedDecimal9, actual3);
//            System.out.println("testAppendDoubleOnce(" + value + ", \"" + actual + "\", \"" + actual2 + "\", \"" + actualg + "\", \"" + actual3 + "\");");

        } finally {
            a.releaseLast();
        }
    }
}
