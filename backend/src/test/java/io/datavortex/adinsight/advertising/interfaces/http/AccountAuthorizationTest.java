package io.datavortex.adinsight.advertising.interfaces.http;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.advertising.domain.model.SourcePlatform;
import io.datavortex.adinsight.advertising.infrastructure.config.AdvertisingConfiguration;
import io.datavortex.adinsight.advertising.interfaces.http.controller.AccountController;
import io.datavortex.adinsight.identityaccess.application.port.MembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.infrastructure.config.SecurityConfiguration;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import({SecurityConfiguration.class, ErrorWriter.class, AdvertisingConfiguration.class})
class AccountAuthorizationTest {
    private static final String TENANT_A = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT_B = "00000000-0000-0000-0000-000000000002";
    @Autowired MockMvc mvc;
    @MockitoBean AccountCatalog accounts;
    @MockitoBean MembershipDirectory memberships;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/accounts").servletPath("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(accounts, memberships);
    }

    @Test
    @WithMockUser(username = "user-a")
    void missingAndMalformedTenantAreRejected() throws Exception {
        for (String header : List.of("", "not-a-uuid", "1-1-1-1-1")) {
            mvc.perform(
                            get("/api/v1/accounts")
                                    .servletPath("/api/v1/accounts")
                                    .header("X-Tenant-Id", header))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TENANT"));
        }
        verifyNoInteractions(accounts, memberships);
    }

    @Test
    @WithMockUser(username = "user-a")
    void otherTenantCannotBeSelected() throws Exception {
        when(memberships.role(UUID.fromString(TENANT_B), "user-a")).thenReturn(Optional.empty());
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
        verifyNoInteractions(accounts);
    }

    @Test
    @WithMockUser(username = "user-a")
    void memberReadsOnlySelectedTenantAndNoCredentials() throws Exception {
        when(memberships.role(UUID.fromString(TENANT_A), "user-a"))
                .thenReturn(Optional.of(TenantRole.VIEWER));
        when(accounts.list(UUID.fromString(TENANT_A)))
                .thenReturn(
                        List.of(
                                new AdAccount(
                                        UUID.randomUUID(),
                                        UUID.fromString(TENANT_A),
                                        SourcePlatform.MOCK_REST,
                                        "source-001",
                                        Currency.getInstance("VND"),
                                        ZoneId.of("Asia/Ho_Chi_Minh"),
                                        "ACTIVE")));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceAccountId").value("source-001"))
                .andExpect(jsonPath("$.items[0].credentialRef").doesNotExist())
                .andExpect(header().exists("X-Request-Id"));
        verify(accounts).list(UUID.fromString(TENANT_A));
        verifyNoMoreInteractions(accounts);
    }

    @Test
    @WithMockUser(username = "user-a")
    void dependencyFailureCannotLookLikeAnEmptyAccountList() throws Exception {
        when(memberships.role(UUID.fromString(TENANT_A), "user-a"))
                .thenReturn(Optional.of(TenantRole.ANALYST));
        when(accounts.list(UUID.fromString(TENANT_A)))
                .thenThrow(new DataAccessResourceFailureException("sensitive database detail"));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Data dependency unavailable"));
    }

    @Test
    @WithMockUser(username = "user-a")
    void membershipStoreFailureFailsClosed() throws Exception {
        when(memberships.role(UUID.fromString(TENANT_A), "user-a"))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
        verifyNoInteractions(accounts);
    }
}
