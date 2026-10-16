package io.datavortex.adinsight.identityaccess.interfaces.http.controller;

import io.datavortex.adinsight.identityaccess.interfaces.http.dto.CsrfTokenResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
public class CsrfController {
    @GetMapping(value = "/api/v1/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public CsrfTokenResponse token(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }
}
