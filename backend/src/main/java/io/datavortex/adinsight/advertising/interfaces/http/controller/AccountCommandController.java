package io.datavortex.adinsight.advertising.interfaces.http.controller;

import io.datavortex.adinsight.advertising.application.service.MetadataService;
import io.datavortex.adinsight.advertising.interfaces.http.dto.AccountCreatedResponse;
import io.datavortex.adinsight.advertising.interfaces.http.dto.CreateAccountRequest;
import io.datavortex.adinsight.advertising.interfaces.http.mapper.AccountResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountCommandController {
    private final MetadataService metadata;

    public AccountCommandController(MetadataService metadata) {
        this.metadata = metadata;
    }

    @PostMapping
    public ResponseEntity<AccountCreatedResponse> create(
            @Valid @RequestBody CreateAccountRequest body, HttpServletRequest request) {
        var account =
                metadata.createAccount(
                        (TenantScope) request.getAttribute(TenantScope.class.getName()),
                        body.toDomain(),
                        UUID.fromString((String) request.getAttribute("requestId")));
        return ResponseEntity.status(201)
                .body(
                        new AccountCreatedResponse(
                                AccountResponseMapper.from(account),
                                (String) request.getAttribute("requestId")));
    }
}
