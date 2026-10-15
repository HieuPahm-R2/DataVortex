package io.datavortex.adinsight.ingestion.domain.model;

/** Compiled SQL is persisted with the snapshot, so recovery cannot switch code revisions. */
public record WarehousePlan(
        CloudSnapshot snapshot,
        String compilationId,
        String qualitySql,
        String publishSql,
        String project,
        String dataset,
        String location,
        long maximumBytesBilled) {
    public WarehousePlan(
            CloudSnapshot snapshot, String compilationId, String qualitySql, String publishSql) {
        this(snapshot, compilationId, qualitySql, publishSql, null, null, null, 0);
    }
}
