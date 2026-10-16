package io.datavortex.adinsight.budgeting.interfaces.http.controller;

import io.datavortex.adinsight.budgeting.application.service.BudgetService;
import io.datavortex.adinsight.budgeting.interfaces.http.dto.*;
import io.datavortex.adinsight.budgeting.interfaces.http.mapper.BudgetResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
        value = "/api/v1/campaigns/{id}/budget-plan",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class BudgetController {
    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    public BudgetPlanResponse get(
            @PathVariable UUID id,
            @RequestParam LocalDate periodStart,
            @RequestParam LocalDate periodEnd,
            HttpServletRequest request) {
        return BudgetResponseMapper.from(
                budgets.get(scope(request), id, periodStart, periodEnd), requestId(request));
    }

    @PutMapping
    public BudgetPlanResponse save(
            @PathVariable UUID id,
            @Valid @RequestBody SaveBudgetRequest body,
            HttpServletRequest request) {
        return BudgetResponseMapper.from(
                budgets.save(
                        scope(request), id, body.toDomain(), UUID.fromString(requestId(request))),
                requestId(request));
    }

    private static TenantScope scope(HttpServletRequest r) {
        return (TenantScope) r.getAttribute(TenantScope.class.getName());
    }

    private static String requestId(HttpServletRequest r) {
        return (String) r.getAttribute("requestId");
    }
}
