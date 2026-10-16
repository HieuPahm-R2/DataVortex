package io.datavortex.adinsight.identityaccess.infrastructure.config;

import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.infrastructure.security.ApiAuthenticationEntryPoint;
import io.datavortex.adinsight.identityaccess.interfaces.http.TenantFilter;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@Profile("local")
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, IMembershipDirectory memberships, ErrorWriter errors)
            throws Exception {
        // Basic authenticates each request. Avoid implicit session authentication
        // clearing
        // the session's CSRF token after every successful mutation; CSRF remains
        // enabled.
        return http.securityContext(
                        context ->
                                context.securityContextRepository(
                                        new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(
                        basic ->
                                basic.authenticationEntryPoint(
                                        new ApiAuthenticationEntryPoint(errors, "Basic")))
                .exceptionHandling(
                        handler ->
                                handler.accessDeniedHandler(
                                        (request, response, exception) ->
                                                errors.write(
                                                        request,
                                                        response,
                                                        403,
                                                        "FORBIDDEN",
                                                        "Access denied")))
                .addFilterAfter(
                        new TenantFilter(memberships, errors), AnonymousAuthenticationFilter.class)
                .build();
    }
}
