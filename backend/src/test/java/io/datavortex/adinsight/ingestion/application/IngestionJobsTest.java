package io.datavortex.adinsight.ingestion.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.application.service.IngestionJobService;
import io.datavortex.adinsight.ingestion.domain.model.DateRange;
import io.datavortex.adinsight.ingestion.domain.model.ReportKind;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestionJobsTest {
    private final AccountCatalog accounts = mock(AccountCatalog.class);
    private final IngestionJobRepository repository = mock(IngestionJobRepository.class);
    private final IngestionJobService jobs = new IngestionJobService(accounts, repository);
    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final DateRange dates =
            new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14));

    @Test
    void analystCannotSubmitAndViewerCannotReadJobs() {
        assertThatThrownBy(
                        () ->
                                jobs.submit(
                                        scope(TenantRole.ANALYST),
                                        account,
                                        ReportKind.CAMPAIGN,
                                        dates,
                                        "key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(BusinessException.Code.FORBIDDEN));
        assertThatThrownBy(() -> jobs.get(scope(TenantRole.VIEWER), UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(BusinessException.Code.FORBIDDEN));
        verifyNoInteractions(accounts, repository);
    }

    @Test
    void accountMustBelongToAuthenticatedTenant() {
        when(accounts.find(tenant, account)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                jobs.submit(
                                        scope(TenantRole.TENANT_ADMIN),
                                        account,
                                        ReportKind.CAMPAIGN,
                                        dates,
                                        "key"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(BusinessException.Code.NOT_FOUND));
        verifyNoInteractions(repository);
    }

    @Test
    void jobReadNeverDropsTenantScope() {
        UUID jobId = UUID.randomUUID();
        when(repository.find(tenant, jobId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> jobs.get(scope(TenantRole.ANALYST), jobId))
                .isInstanceOf(BusinessException.class);
        verify(repository).find(tenant, jobId);
    }

    @Test
    void reversedDateRangeIsInvalid() {
        assertThatThrownBy(() -> new DateRange(dates.to(), dates.from()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom");
    }

    private TenantScope scope(TenantRole role) {
        return new TenantScope(tenant, "user", role);
    }
}
