package io.datavortex.adinsight.advertising;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.datavortex.adinsight.advertising.application.port.IMetadataCatalog;
import io.datavortex.adinsight.advertising.application.service.MetadataService;
import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.advertising.interfaces.http.dto.*;
import io.datavortex.adinsight.budgeting.application.port.IBudgetPlans;
import io.datavortex.adinsight.budgeting.application.service.BudgetService;
import io.datavortex.adinsight.budgeting.domain.model.*;
import io.datavortex.adinsight.budgeting.interfaces.http.dto.SaveBudgetRequest;
import io.datavortex.adinsight.identityaccess.domain.model.*;
import io.datavortex.adinsight.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataBudgetTest {
    ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void patchDistinguishesAbsentFieldsFromExplicitNull() throws Exception {
        var tags =
                json.readValue(
                                "{\"expectedVersion\":1,\"localTags\":[\"tag\"]}",
                                PatchLocalSettingsRequest.class)
                        .toDomain();
        assertThat(tags.replaceNote()).isFalse();
        assertThat(tags.replaceTags()).isTrue();
        var clear =
                json.readValue(
                                "{\"expectedVersion\":1,\"localNote\":null}",
                                PatchLocalSettingsRequest.class)
                        .toDomain();
        assertThat(clear.replaceNote()).isTrue();
        assertThat(clear.note()).isNull();
        assertThat(clear.replaceTags()).isFalse();
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                                "{\"expectedVersion\":1}",
                                                PatchLocalSettingsRequest.class)
                                        .toDomain())
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void budgetRequestMapsItsAcceptedDomainPolicy() throws Exception {
        var value =
                json.readValue(
                                """
   {"periodStart":"2026-10-01","periodEnd":"2026-10-31","amount":"1000000.123456","currency":"VND",
    "expectedRevision":0,"active":true,"kpiTargets":[{"metric":"ROAS","operator":"GTE","targetValue":"2.123456","minSamples":0}]}
   """,
                                SaveBudgetRequest.class)
                        .toDomain();
        assertThat(value.expectedRevision()).isZero();
        assertThat(value.amount()).isEqualByComparingTo("1000000.123456");
    }

    @Test
    void membershipDenialHappensBeforeMetadataOrBudgetWrites() {
        var catalog = mock(IMetadataCatalog.class);
        var plans = mock(IBudgetPlans.class);
        var scope =
                new TenantScope(
                        UUID.randomUUID(), UUID.randomUUID().toString(), TenantRole.ANALYST);
        var metadata = new MetadataService(catalog);
        var budgets = new BudgetService(plans);
        assertThatThrownBy(
                        () ->
                                metadata.patch(
                                        scope,
                                        UUID.randomUUID(),
                                        new LocalSettingsPatch(1, true, "note", false, List.of()),
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(BusinessException.Code.FORBIDDEN));
        assertThatThrownBy(
                        () ->
                                metadata.createAccount(
                                        scope,
                                        new NewAccount(
                                                SourcePlatform.MOCK_REST,
                                                "001",
                                                Currency.getInstance("VND"),
                                                ZoneId.of("UTC"),
                                                null),
                                        UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> budgets.save(scope, UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(catalog, plans);
    }

    @Test
    void numericPolicyRejectsRoundingOverflowAndNegativeSamples() {
        assertThat(
                        new KpiTarget(
                                        KpiMetric.ROAS,
                                        KpiOperator.GTE,
                                        new BigDecimal("0.000001"),
                                        0)
                                .targetValue())
                .isEqualByComparingTo("0.000001");
        for (String invalid : List.of("0", "-1", "0.0000001", "100000000000000.000000"))
            assertThatThrownBy(
                            () ->
                                    new KpiTarget(
                                            KpiMetric.CPA,
                                            KpiOperator.LTE,
                                            new BigDecimal(invalid),
                                            0))
                    .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(
                        () -> new KpiTarget(KpiMetric.SPEND, KpiOperator.LTE, BigDecimal.ONE, -1))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void accountAcceptsOnlyReferencesAndNoteEnforcesUtf8StorageCapacity() {
        new NewAccount(
                SourcePlatform.GOOGLE_ADS,
                "0001",
                Currency.getInstance("USD"),
                ZoneId.of("UTC"),
                "projects/demo/locations/us-central1/secrets/provider/versions/latest");
        assertThatThrownBy(
                        () ->
                                new NewAccount(
                                        SourcePlatform.GOOGLE_ADS,
                                        "0001",
                                        Currency.getInstance("USD"),
                                        ZoneId.of("UTC"),
                                        "secret-token"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(
                        () -> new LocalSettingsPatch(1, true, "😀".repeat(16384), false, List.of()))
                .isInstanceOf(InvalidInputException.class);
    }
}
