package io.datavortex.adinsight.identityaccess.interfaces.http.controller;

import io.datavortex.adinsight.identityaccess.interfaces.http.dto.CsrfTokenResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/v1/csrf")
    public CsrfTokenResponse token(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }
}
