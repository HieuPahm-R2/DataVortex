package io.datavortex.adinsight.ingestion.domain.model;

/** Immutable remote request. Persist before submit; its ID survives every ownership change. */
public record WarehouseCommand(
        String jobId,
        String project,
        String location,
        WarehouseStep step,
        String destination,
        String sourceUri,
        String sql,
        CloudSnapshot snapshot,
        long maximumBytesBilled) {}
