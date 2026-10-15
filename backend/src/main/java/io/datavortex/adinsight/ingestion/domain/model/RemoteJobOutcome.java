package io.datavortex.adinsight.ingestion.domain.model;

public record RemoteJobOutcome(State state, String errorCode, boolean retryable, long rowsLoaded) {
    public enum State {
        MISSING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static RemoteJobOutcome missing() {
        return new RemoteJobOutcome(State.MISSING, null, false, 0);
    }

    public static RemoteJobOutcome running() {
        return new RemoteJobOutcome(State.RUNNING, null, false, 0);
    }
}
