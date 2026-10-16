package io.datavortex.adinsight.budgeting.interfaces.http.dto;

import io.datavortex.adinsight.budgeting.domain.model.BudgetChange;
import io.datavortex.adinsight.shared.domain.InvalidInputException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

public record SaveBudgetRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 6)
                BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotNull Boolean active,
        @NotNull List<@NotNull @Valid KpiTargetRequest> kpiTargets) {
    public BudgetChange toDomain() {
        try {
            return new BudgetChange(
                    periodStart,
                    periodEnd,
                    amount,
                    Currency.getInstance(currency),
                    expectedRevision,
                    active,
                    kpiTargets.stream().map(KpiTargetRequest::toDomain).toList());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputException("Invalid budget fields");
        }
    }
}
