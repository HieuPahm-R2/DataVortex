package io.datavortex.adinsight.identityaccess.interfaces.http;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.advertising.infrastructure.config.AdvertisingConfiguration;
import io.datavortex.adinsight.advertising.interfaces.http.controller.AccountController;
import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.infrastructure.config.SecurityConfiguration;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@ActiveProfiles("local")
@Import({SecurityConfiguration.class, ErrorWriter.class, AdvertisingConfiguration.class})
class BasicAuthenticationTest {
    @Autowired MockMvc mvc;
    @MockitoBean IAccountCatalog accounts;
    @MockitoBean IMembershipDirectory memberships;
    @MockitoBean UserDetailsService users;

    @Test
    void authenticationDatabaseFailureIsUnavailableWithoutPasswordChallenge() throws Exception {
        when(users.loadUserByUsername("person@example.test"))
                .thenThrow(new DataAccessResourceFailureException("password=secret SELECT users"));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .with(httpBasic("person@example.test", "test-only")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("secret"))));
        verifyNoInteractions(accounts, memberships);
    }

    @Test
    void invalidCredentialsRemainUnauthorizedAndSafe() throws Exception {
        when(users.loadUserByUsername("person@example.test"))
                .thenThrow(new UsernameNotFoundException("sensitive principal lookup"));
        mvc.perform(get("/api/v1/accounts").with(httpBasic("person@example.test", "test-only")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"AdInsight\""))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        verifyNoInteractions(accounts, memberships);
    }
}
