package io.datavortex.adinsight.advertising.interfaces.http.dto;

import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.shared.domain.InvalidInputException;
import jakarta.validation.constraints.*;
import java.time.ZoneId;
import java.util.Currency;

public record CreateAccountRequest(
        @NotNull SourcePlatform platform,
        @NotBlank @Size(max = 255) String sourceAccountId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 64) String timezone,
        @Size(max = 512) String credentialRef) {
    public NewAccount toDomain() {
        try {
            return new NewAccount(
                    platform,
                    sourceAccountId,
                    Currency.getInstance(currency),
                    ZoneId.of(timezone),
                    credentialRef);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new InvalidInputException("Invalid account metadata");
        }
    }
}
