package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.*;
import java.time.Duration;
import java.util.Optional;

public interface WarehouseQueue {
    Optional<WarehouseTask> claim(String owner, Duration duration);

    void renew(WarehouseTask task, Duration duration);

    void savePlan(WarehouseTask task, WarehousePlan plan);

    void saveCommand(WarehouseTask task, WarehouseCommand command);

    void advance(WarehouseTask task, long rowsLoaded);

    /** Keeps the occupied slot and command; only the same job can recover it. */
    void defer(WarehouseTask task, Duration delay, String errorCode);

    /** Only after a confirmed terminal remote failure, or before any command is persisted. */
    void retryTerminal(WarehouseTask task, String errorCode);

    void failTerminal(WarehouseTask task, String errorCode);

    void complete(WarehouseTask task);
}
