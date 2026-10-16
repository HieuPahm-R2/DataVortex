package io.datavortex.adinsight.shared.domain;

/** Invalid caller input, distinct from programming/configuration argument failures. */
public final class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}
