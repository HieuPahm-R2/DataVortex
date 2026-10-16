package io.datavortex.adinsight.budgeting.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.math.BigDecimal;

public record KpiTarget(
        KpiMetric metric, KpiOperator operator, BigDecimal targetValue, long minSamples) {
    public KpiTarget {
        if (metric == null || operator == null || !validDecimal(targetValue) || minSamples < 0)
            throw new InvalidInputException(
                    "KPI metric/operator, positive DECIMAL(20,6) target and nonnegative samples required");
    }

    public static boolean validDecimal(BigDecimal value) {
        return value != null
                && value.signum() > 0
                && value.scale() <= 6
                && value.precision() - value.scale() <= 14;
    }
}
