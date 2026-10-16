package io.datavortex.adinsight.analytics.domain.model;

import java.time.Instant;
import java.util.UUID;

public record InputWatermark(
        UUID accountId, long runSequence, UUID publishedRunId, Instant dataAsOf) {}
