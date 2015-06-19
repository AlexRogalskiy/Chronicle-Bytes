/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.bytes;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

/**
 * User: peter.lawrey Date: 24/12/13 Time: 19:43
 */
/*
buffers 128 KB took an average of 18,441 ns for heap ByteBuffer, 33,683 ns for direct ByteBuffer and 1,761 for DirectStore
buffers 128 KB took an average of 13,062 ns for heap ByteBuffer, 17,855 ns for direct ByteBuffer and 903 for DirectStore
buffers 128 KB took an average of 12,809 ns for heap ByteBuffer, 21,602 ns for direct ByteBuffer and 922 for DirectStore
buffers 128 KB took an average of 10,768 ns for heap ByteBuffer, 21,444 ns for direct ByteBuffer and 894 for DirectStore
buffers 128 KB took an average of 8,739 ns for heap ByteBuffer, 22,684 ns for direct ByteBuffer and 890 for DirectStore
 */
public class AllocationRatesTest {
    static final int BUFFER_SIZE = 128 * 1024;
    static final int ALLOCATIONS = 10000;
    public static final int BATCH = 10;

    @Test
    public void compareAllocationRates() {
        for (int i = 0; i < 5; i++) {
            long timeHBB = timeHeapByteBufferAllocations();
            long timeDBB = timeDirectByteBufferAllocations();
            long timeDS = timeDirectStoreAllocations();
            System.out.printf("buffers %d KB took an average of %,d ns for heap ByteBuffer, %,d ns for direct ByteBuffer and %,d for DirectStore%n",
                    BUFFER_SIZE / 1024, timeHBB / ALLOCATIONS, timeDBB / ALLOCATIONS, timeDS / ALLOCATIONS);
        }
    }

    private long timeHeapByteBufferAllocations() {
        long start = System.nanoTime();
        for (int i = 0; i < ALLOCATIONS; i += BATCH) {
            ByteBuffer[] bb = new ByteBuffer[BATCH];
            for (int j = 0; j < BATCH; j++)
                bb[j] = ByteBuffer.allocate(BUFFER_SIZE);
        }
        return System.nanoTime() - start;
    }

    private long timeDirectByteBufferAllocations() {
        long start = System.nanoTime();
        for (int i = 0; i < ALLOCATIONS; i += BATCH) {
            ByteBuffer[] bb = new ByteBuffer[BATCH];
            for (int j = 0; j < BATCH; j++)
                bb[j] = ByteBuffer.allocateDirect(BUFFER_SIZE);
        }
        return System.nanoTime() - start;
    }

    private long timeDirectStoreAllocations() {
        long start = System.nanoTime();
        for (int i = 0; i < ALLOCATIONS; i += BATCH) {
            NativeBytesStore[] ds = new NativeBytesStore[BATCH];
            for (int j = 0; j < BATCH; j++)
                ds[j] = NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity(BUFFER_SIZE);
            for (int j = 0; j < BATCH; j++) {
                ds[j].release();
                assertEquals(0, ds[j].refCount());
            }
        }
        return System.nanoTime() - start;
    }
}
