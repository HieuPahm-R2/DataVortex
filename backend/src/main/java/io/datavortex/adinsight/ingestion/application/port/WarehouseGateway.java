package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.*;

public interface WarehouseGateway {
    WarehousePlan prepare(IngestionJob job, Runnable heartbeat);

    WarehouseCommand command(WarehouseTask task);

    RemoteJobOutcome inspect(WarehouseCommand command);

    void submit(WarehouseCommand command);
}
