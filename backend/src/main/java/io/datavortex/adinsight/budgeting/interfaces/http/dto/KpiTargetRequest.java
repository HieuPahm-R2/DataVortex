package io.datavortex.adinsight.budgeting.interfaces.http.dto;

import io.datavortex.adinsight.budgeting.domain.model.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record KpiTargetRequest(
        @NotNull KpiMetric metric,
        @NotNull KpiOperator operator,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 6)
                BigDecimal targetValue,
        @NotNull @PositiveOrZero Long minSamples) {
    public KpiTarget toDomain() {
        return new KpiTarget(metric, operator, targetValue, minSamples);
    }
}
