package io.datavortex.adinsight.advertising.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.time.ZoneId;
import java.util.Currency;

public record NewAccount(
        SourcePlatform platform,
        String sourceAccountId,
        Currency currency,
        ZoneId timezone,
        String credentialRef) {
    public NewAccount {
        if (platform == null
                || currency == null
                || timezone == null
                || sourceAccountId == null
                || sourceAccountId.isBlank()
                || sourceAccountId.codePointCount(0, sourceAccountId.length()) > 255)
            throw new InvalidInputException("Valid account metadata required");
        if (credentialRef != null
                && (credentialRef.length() > 512
                        || !credentialRef.matches(
                                "projects/[^/\\s]+/(?:locations/[^/\\s]+/)?secrets/[^/\\s]+(?:/versions/[^/\\s]+)?")))
            throw new InvalidInputException(
                    "credentialRef must reference a Secret Manager resource, never a token");
    }
}
