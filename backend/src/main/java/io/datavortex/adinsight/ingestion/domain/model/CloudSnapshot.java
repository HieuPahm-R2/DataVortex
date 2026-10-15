package io.datavortex.adinsight.ingestion.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CloudSnapshot(
        UUID runId,
        UUID tenantId,
        UUID accountId,
        String platform,
        LocalDate dateFrom,
        LocalDate dateTo,
        String currency,
        String accountTimezone,
        long runSequence,
        Instant fetchedAt,
        long rowCount,
        String rawManifestSha256,
        String rawManifestUri,
        String parquetUri,
        String normalizedManifestUri) {}
