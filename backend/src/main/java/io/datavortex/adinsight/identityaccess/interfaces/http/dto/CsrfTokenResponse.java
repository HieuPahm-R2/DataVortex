package io.datavortex.adinsight.identityaccess.interfaces.http.dto;

public record CsrfTokenResponse(String headerName, String token) {}
