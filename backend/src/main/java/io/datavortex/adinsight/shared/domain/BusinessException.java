package io.datavortex.adinsight.shared.domain;

public final class BusinessException extends RuntimeException {
    public enum Code {
        FORBIDDEN,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        LEASE_LOST
    }

    private final Code code;

    public BusinessException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
