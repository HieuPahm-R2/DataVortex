package io.datavortex.adinsight.ingestion.application.service;

import io.datavortex.adinsight.ingestion.application.port.WarehouseGateway;
import io.datavortex.adinsight.ingestion.application.port.WarehouseQueue;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Durable publisher slot serializes warehouse writers, even across ambiguous remote outcomes. */
public final class WarehouseWorker {
    private final WarehouseQueue queue;
    private final WarehouseGateway gateway;
    private final String owner;
    private final Duration duration;

    public WarehouseWorker(
            WarehouseQueue queue, WarehouseGateway gateway, String owner, Duration duration) {
        JobLease.validateDuration(duration);
        this.queue = queue;
        this.gateway = gateway;
        this.owner = owner;
        this.duration = duration;
    }

    public boolean pollOnce() {
        var claimed = queue.claim(owner, duration);
        if (claimed.isEmpty()) return false;
        WarehouseTask task = claimed.orElseThrow();
        AtomicReference<RuntimeException> lost = new AtomicReference<>();
        Runnable check =
                () -> {
                    if (lost.get() != null) throw lost.get();
                    if (Thread.currentThread().isInterrupted())
                        throw new WarehouseFailure("WORKER_INTERRUPTED", true);
                    queue.renew(task, duration);
                };
        try (var heartbeats = Executors.newSingleThreadScheduledExecutor()) {
            heartbeats.scheduleAtFixedRate(
                    () -> {
                        try {
                            queue.renew(task, duration);
                        } catch (RuntimeException error) {
                            lost.compareAndSet(null, error);
                        }
                    },
                    duration.toMillis() / 3,
                    duration.toMillis() / 3,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                if (task.step() == WarehouseStep.CHECKPOINT) {
                    check.run();
                    queue.complete(task);
                    return true;
                }
                if (task.plan() == null) {
                    var plan = gateway.prepare(task.lease().job(), check);
                    check.run();
                    queue.savePlan(task, plan);
                    queue.defer(task, Duration.ofSeconds(1), null);
                    return true;
                }
                if (task.command() == null) {
                    var command = gateway.command(task);
                    check.run();
                    queue.saveCommand(task, command);
                    // Reclaim the persisted request, rather than running an uncommitted request.
                    queue.defer(task, Duration.ofSeconds(1), null);
                    return true;
                }
                check.run();
                var outcome = gateway.inspect(task.command());
                check.run();
                switch (outcome.state()) {
                    case MISSING -> {
                        gateway.submit(task.command());
                        check.run();
                        queue.defer(task, Duration.ofSeconds(1), null);
                    }
                    case RUNNING -> queue.defer(task, Duration.ofSeconds(1), null);
                    case SUCCEEDED -> {
                        if (task.step() == WarehouseStep.LOAD
                                && outcome.rowsLoaded() != task.plan().snapshot().rowCount())
                            queue.failTerminal(task, "STAGING_ROW_COUNT_MISMATCH");
                        else queue.advance(task, outcome.rowsLoaded());
                    }
                    case FAILED -> {
                        if (outcome.retryable()) queue.retryTerminal(task, outcome.errorCode());
                        else queue.failTerminal(task, outcome.errorCode());
                    }
                }
            } catch (WarehouseFailure failure) {
                check.run();
                // After persistence even a 403/timeout is an unknown outcome, not evidence of
                // failure.
                if (task.command() != null)
                    queue.defer(task, Duration.ofSeconds(1), failure.getMessage());
                else if (failure.retryable()) queue.retryTerminal(task, failure.getMessage());
                else queue.failTerminal(task, failure.getMessage());
            } finally {
                heartbeats.shutdownNow();
            }
        }
        return true;
    }
}
