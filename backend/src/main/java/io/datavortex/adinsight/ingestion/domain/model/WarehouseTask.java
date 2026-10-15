package io.datavortex.adinsight.ingestion.domain.model;

public record WarehouseTask(
        JobLease lease,
        WarehouseStep step,
        int stageAttempt,
        WarehousePlan plan,
        WarehouseCommand command) {
    public String remoteJobId() {
        if (plan == null) throw new IllegalStateException("Snapshot binding required");
        return "adinsight_"
                + plan.snapshot().runId().toString().replace("-", "")
                + "_"
                + step.name().toLowerCase(java.util.Locale.ROOT)
                + "_"
                + stageAttempt;
    }
}
