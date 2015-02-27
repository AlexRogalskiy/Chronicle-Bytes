package net.openhft.chronicle.bytes;

public class IORuntimeException extends RuntimeException {
    public IORuntimeException(String message) {
        super(message);
    }

    public IORuntimeException(Exception e) {
        super(e);
    }
}
