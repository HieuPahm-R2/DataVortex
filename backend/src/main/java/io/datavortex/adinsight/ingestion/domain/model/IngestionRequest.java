package io.datavortex.adinsight.ingestion.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record IngestionRequest(
        UUID tenantId,
        UUID accountId,
        ReportKind reportKind,
        DateRange dates,
        String idempotencyKey) {
    public IngestionRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reportKind, "reportKind");
        Objects.requireNonNull(dates, "dates");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 100 characters");
        }
    }

    public String fingerprint() {
        String canonical =
                String.join(
                        "|",
                        tenantId.toString(),
                        accountId.toString(),
                        reportKind.name(),
                        dates.from().toString(),
                        dates.to().toString());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK must support SHA-256", impossible);
        }
    }
}
