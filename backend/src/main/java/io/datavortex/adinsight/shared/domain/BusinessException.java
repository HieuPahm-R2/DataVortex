package io.datavortex.adinsight.shared.domain;

public final class BusinessException extends RuntimeException {
    public enum Code {
        FORBIDDEN,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        OPTIMISTIC_CONFLICT,
        RESOURCE_CONFLICT,
        BUDGET_PERIOD_CONFLICT,
        WAREHOUSE_UNAVAILABLE,
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
