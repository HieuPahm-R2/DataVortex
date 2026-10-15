package io.datavortex.adinsight.advertising.interfaces.http.controller;

import io.datavortex.adinsight.advertising.application.service.AccountQueryService;
import io.datavortex.adinsight.advertising.interfaces.http.dto.AccountListResponse;
import io.datavortex.adinsight.advertising.interfaces.http.mapper.AccountResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountQueryService accounts;

    public AccountController(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public AccountListResponse list(HttpServletRequest request) {
        var scope = (TenantScope) request.getAttribute(TenantScope.class.getName());
        return AccountResponseMapper.from(
                accounts.list(scope), (String) request.getAttribute("requestId"));
    }
}
