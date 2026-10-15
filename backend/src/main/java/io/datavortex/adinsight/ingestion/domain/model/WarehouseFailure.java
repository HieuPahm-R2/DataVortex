package io.datavortex.adinsight.ingestion.domain.model;

/** An ambiguous dependency failure is never a terminal remote-job failure. */
public final class WarehouseFailure extends RuntimeException {
    private final boolean retryable;

    public WarehouseFailure(String code, boolean retryable) {
        super(code);
        if (!code.matches("[A-Z][A-Z0-9_]{0,99}"))
            throw new IllegalArgumentException("Safe code required");
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
