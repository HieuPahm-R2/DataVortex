package io.datavortex.adinsight.ingestion.domain.model;

public enum WarehouseStep {
    LOAD,
    MANIFEST,
    TRANSFORM,
    PUBLISH,
    CHECKPOINT;

    public IngestionStage stage() {
        return switch (this) {
            case LOAD, MANIFEST -> IngestionStage.LOAD;
            case TRANSFORM -> IngestionStage.TRANSFORM;
            case PUBLISH -> IngestionStage.PUBLISH;
            case CHECKPOINT -> IngestionStage.CHECKPOINT;
        };
    }

    public WarehouseStep next() {
        if (this == CHECKPOINT) throw new IllegalStateException("Checkpoint is terminal");
        return values()[ordinal() + 1];
    }
}
