package net.openhft.chronicle.bytes;

public interface RandomCommon<S extends RandomCommon<S>> {
    /**
     * @return the highest offset or position allowed for this buffer.
     */
    default long limit() {
        return capacity();
    }

    /**
     * @return the highest limit allowed for this buffer.
     */
    long capacity();

    boolean compareAndSwapInt(long offset, int expected, int value);

    boolean compareAndSwapLong(long offset, long expected, long value);

    /**
     * Obtain the underlying address.  This is for expert users only.
     *
     * @return the underlying address of the buffer
     * @throws UnsupportedOperationException if the underlying buffer is on the heap
     */
    long address() throws UnsupportedOperationException;
}
