package io.datavortex.adinsight.advertising.interfaces.http.controller;

import io.datavortex.adinsight.advertising.application.service.AccountQueryService;
import io.datavortex.adinsight.advertising.interfaces.http.dto.AccountListResponse;
import io.datavortex.adinsight.advertising.interfaces.http.mapper.AccountResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountController {
    private final AccountQueryService accounts;

    public AccountController(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public AccountListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        var scope = (TenantScope) request.getAttribute(TenantScope.class.getName());
        return AccountResponseMapper.from(
                accounts.list(scope, new PageQuery(page, size)),
                (String) request.getAttribute("requestId"));
    }
}
