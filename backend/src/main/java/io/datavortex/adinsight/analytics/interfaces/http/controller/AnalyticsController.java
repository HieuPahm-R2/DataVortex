package io.datavortex.adinsight.analytics.interfaces.http.controller;

import io.datavortex.adinsight.analytics.application.service.AnalyticsService;
import io.datavortex.adinsight.analytics.domain.model.AnalyticsQuery;
import io.datavortex.adinsight.analytics.interfaces.http.dto.AnalyticsResponse;
import io.datavortex.adinsight.analytics.interfaces.http.mapper.AnalyticsResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.InvalidInputException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public AnalyticsResponse summary(
            @RequestParam LocalDate dateFrom,
            @RequestParam LocalDate dateTo,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) UUID accountId,
            HttpServletRequest request) {
        return read(
                new AnalyticsQuery(dateFrom, dateTo, currency(currency), accountId, null, false),
                request);
    }

    @GetMapping("/campaigns/{id}/daily")
    public AnalyticsResponse daily(
            @PathVariable UUID id,
            @RequestParam LocalDate dateFrom,
            @RequestParam LocalDate dateTo,
            HttpServletRequest request) {
        return read(new AnalyticsQuery(dateFrom, dateTo, null, null, id, true), request);
    }

    private AnalyticsResponse read(AnalyticsQuery query, HttpServletRequest request) {
        return AnalyticsResponseMapper.from(
                query,
                analytics.query(
                        (TenantScope) request.getAttribute(TenantScope.class.getName()), query),
                (String) request.getAttribute("requestId"));
    }

    private static Currency currency(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[A-Z]{3}")) throw new IllegalArgumentException();
            return Currency.getInstance(value);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputException("Valid ISO currency required");
        }
    }
}
