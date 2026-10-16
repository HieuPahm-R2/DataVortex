package io.datavortex.adinsight.shared.interfaces.http;

import java.util.List;

public record ApiError(
        String code, String message, String requestId, List<FieldViolation> details) {
    public ApiError {
        details = List.copyOf(details);
    }

    public ApiError(String code, String message, String requestId) {
        this(code, message, requestId, List.of());
    }
}
