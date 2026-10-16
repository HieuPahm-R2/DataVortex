package io.datavortex.adinsight.ingestion.interfaces.http.controller;

import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.ingestion.application.service.IngestionJobService;
import io.datavortex.adinsight.ingestion.domain.model.DateRange;
import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.interfaces.http.dto.CreateIngestionJobRequest;
import io.datavortex.adinsight.ingestion.interfaces.http.dto.IngestionJobResponse;
import io.datavortex.adinsight.ingestion.interfaces.http.mapper.IngestionJobResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/ingestion-jobs", produces = MediaType.APPLICATION_JSON_VALUE)
public class IngestionJobController {
    private final IngestionJobService jobs;

    public IngestionJobController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<IngestionJobResponse> submit(
            @Valid @RequestBody CreateIngestionJobRequest body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        IngestionJob job =
                jobs.submit(
                        scope(request),
                        body.accountId(),
                        body.reportKind(),
                        new DateRange(body.dateFrom(), body.dateTo()),
                        key);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ingestion-jobs/" + job.id()))
                .body(
                        IngestionJobResponseMapper.from(
                                job, (String) request.getAttribute("requestId")));
    }

    @GetMapping("/{id}")
    public IngestionJobResponse get(@PathVariable UUID id, HttpServletRequest request) {
        return IngestionJobResponseMapper.from(
                jobs.get(scope(request), id), (String) request.getAttribute("requestId"));
    }

    private static TenantScope scope(HttpServletRequest request) {
        return (TenantScope) request.getAttribute(TenantScope.class.getName());
    }
}
