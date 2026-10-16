package io.datavortex.adinsight.shared.interfaces.http;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.advertising.infrastructure.config.AdvertisingConfiguration;
import io.datavortex.adinsight.advertising.interfaces.http.controller.AccountController;
import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.infrastructure.config.SecurityConfiguration;
import io.datavortex.adinsight.ingestion.application.service.IngestionJobService;
import io.datavortex.adinsight.ingestion.interfaces.http.controller.IngestionJobController;
import io.datavortex.adinsight.shared.domain.PageQuery;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AccountController.class, IngestionJobController.class})
@ActiveProfiles("local")
@Import({SecurityConfiguration.class, ErrorWriter.class, AdvertisingConfiguration.class})
@WithMockUser(username = "local-user")
class HttpErrorsTest {
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean IAccountCatalog accounts;
    @MockitoBean IMembershipDirectory memberships;
    @MockitoBean IngestionJobService jobs;
    @MockitoBean UserDetailsService users;

    @BeforeEach
    void member() {
        when(memberships.role(TENANT, "local-user"))
                .thenReturn(Optional.of(TenantRole.TENANT_ADMIN));
    }

    @Test
    void unexpectedFailureIsSafeAndCorrelated() throws Exception {
        when(accounts.list(TENANT, new PageQuery(0, 20)))
                .thenThrow(
                        new IllegalStateException("SELECT password_hash FROM users; token=secret"));
        var response =
                mvc.perform(
                                get("/api/v1/accounts")
                                        .servletPath("/api/v1/accounts")
                                        .header("X-Tenant-Id", TENANT))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andExpect(jsonPath("$.details").isEmpty())
                        .andExpect(content().string(not(containsString("secret"))))
                        .andExpect(content().string(not(containsString("SELECT"))))
                        .andReturn()
                        .getResponse();
        org.assertj.core.api.Assertions.assertThat(response.getContentAsString())
                .contains(response.getHeader("X-Request-Id"));
    }

    @Test
    void malformedAndInvalidBodiesHaveSafeErrorsAndFieldDetails() throws Exception {
        mvc.perform(
                        post("/api/v1/ingestion-jobs")
                                .servletPath("/api/v1/ingestion-jobs")
                                .header("X-Tenant-Id", TENANT)
                                .header("Idempotency-Key", "test")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(
                        jsonPath(
                                "$.details[*].field",
                                containsInAnyOrder(
                                        "accountId", "reportKind", "dateFrom", "dateTo")));
        mvc.perform(
                        post("/api/v1/ingestion-jobs")
                                .servletPath("/api/v1/ingestion-jobs")
                                .header("X-Tenant-Id", TENANT)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{secret malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("secret"))));
        verifyNoInteractions(jobs);
    }

    @Test
    void frameworkErrorsKeepStatusHeadersAndEnvelope() throws Exception {
        mvc.perform(
                        put("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT)
                                .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(
                        post("/api/v1/ingestion-jobs")
                                .servletPath("/api/v1/ingestion-jobs")
                                .header("X-Tenant-Id", TENANT)
                                .with(csrf())
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(
                        get("/api/v1/does-not-exist")
                                .servletPath("/api/v1/does-not-exist")
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidPagesNeverReachPersistence() throws Exception {
        for (String query :
                new String[] {
                    "page=-1", "size=0", "size=101", "page=2147483647&size=100", "page=bad"
                }) {
            mvc.perform(
                            get("/api/v1/accounts?" + query)
                                    .servletPath("/api/v1/accounts")
                                    .header("X-Tenant-Id", TENANT))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void unsupportedResponseFormatStillReturnsTheJsonErrorContract() throws Exception {
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT)
                                .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void programmingArgumentFailuresAreNotMisreportedAsBadClientInput() throws Exception {
        when(accounts.list(TENANT, new PageQuery(0, 20)))
                .thenThrow(new IllegalArgumentException("sensitive internal configuration"));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
