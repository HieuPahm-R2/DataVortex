package io.datavortex.adinsight.shared.interfaces.http;

public record ApiError(String code, String message, String requestId) {}
