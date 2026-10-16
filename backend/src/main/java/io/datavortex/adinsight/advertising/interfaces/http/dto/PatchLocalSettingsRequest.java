package io.datavortex.adinsight.advertising.interfaces.http.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.datavortex.adinsight.advertising.domain.model.LocalSettingsPatch;
import io.datavortex.adinsight.shared.domain.InvalidInputException;
import jakarta.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

public record PatchLocalSettingsRequest(
        @NotNull @Positive Long expectedVersion, JsonNode localNote, JsonNode localTags) {
    public LocalSettingsPatch toDomain() {
        if (localNote != null && !localNote.isNull() && !localNote.isTextual())
            throw new InvalidInputException("Local note must be text or null");
        List<String> tags = new ArrayList<>();
        if (localTags != null && !localTags.isNull()) {
            if (!localTags.isArray())
                throw new InvalidInputException("Local tags must be a string array");
            for (var tag : localTags) {
                if (!tag.isTextual()) throw new InvalidInputException("Local tags must be strings");
                tags.add(tag.textValue());
            }
        }
        return new LocalSettingsPatch(
                expectedVersion,
                localNote != null,
                localNote == null || localNote.isNull() ? null : localNote.textValue(),
                localTags != null,
                tags);
    }
}
