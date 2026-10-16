package io.datavortex.adinsight.ingestion.application.service;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.domain.model.DateRange;
import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.IngestionRequest;
import io.datavortex.adinsight.ingestion.domain.model.ReportKind;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.util.UUID;

public final class IngestionJobService {
    private final IAccountCatalog accounts;
    private final IngestionJobRepository jobs;

    public IngestionJobService(IAccountCatalog accounts, IngestionJobRepository jobs) {
        this.accounts = accounts;
        this.jobs = jobs;
    }

    public IngestionJob submit(
            TenantScope scope,
            UUID accountId,
            ReportKind reportKind,
            DateRange dates,
            String idempotencyKey) {
        if (!scope.role().canSubmitIngestion()) {
            throw new BusinessException(FORBIDDEN, "Tenant admin required to submit ingestion");
        }
        accounts.find(scope.tenantId(), accountId)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "Account not found"));
        return jobs.submit(
                new IngestionRequest(
                        scope.tenantId(), accountId, reportKind, dates, idempotencyKey));
    }

    public IngestionJob get(TenantScope scope, UUID jobId) {
        if (!scope.role().canReadIngestion()) {
            throw new BusinessException(
                    FORBIDDEN, "Tenant admin or analyst required to read ingestion");
        }
        return jobs.find(scope.tenantId(), jobId)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "Ingestion job not found"));
    }
}
