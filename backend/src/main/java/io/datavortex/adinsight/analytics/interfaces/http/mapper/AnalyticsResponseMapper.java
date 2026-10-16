package io.datavortex.adinsight.analytics.interfaces.http.mapper;

import io.datavortex.adinsight.analytics.domain.model.*;
import io.datavortex.adinsight.analytics.interfaces.http.dto.*;
import java.math.BigDecimal;
import java.util.*;

public final class AnalyticsResponseMapper {
    private AnalyticsResponseMapper() {}

    public static AnalyticsResponse from(
            AnalyticsQuery query, AnalyticsResult result, String requestId) {
        return new AnalyticsResponse(
                result.source(),
                query.dateFrom(),
                query.dateTo(),
                result.dataAsOf(),
                result.inputWatermark(),
                result.groups().stream()
                        .map(
                                group ->
                                        new CurrencyMetricsResponse(
                                                group.currency(),
                                                values(group.totals()),
                                                group.days().stream()
                                                        .map(
                                                                day ->
                                                                        new DailyMetricsResponse(
                                                                                day.date(),
                                                                                values(
                                                                                        day
                                                                                                .metrics())))
                                                        .toList()))
                        .toList(),
                requestId);
    }

    private static Map<String, String> values(Metrics m) {
        var value = new LinkedHashMap<String, String>();
        value.put("impressions", decimal(m.impressions()));
        value.put("clicks", decimal(m.clicks()));
        value.put("conversions", decimal(m.conversions()));
        value.put("cost", decimal(m.cost()));
        value.put("conversionValue", decimal(m.conversionValue()));
        value.put("ctr", decimal(m.ctr()));
        value.put("cpc", decimal(m.cpc()));
        value.put("cpa", decimal(m.cpa()));
        value.put("roas", decimal(m.roas()));
        value.put("cvr", decimal(m.cvr()));
        return Collections.unmodifiableMap(value);
    }

    private static String decimal(BigDecimal number) {
        return number == null ? null : number.toPlainString();
    }
}
