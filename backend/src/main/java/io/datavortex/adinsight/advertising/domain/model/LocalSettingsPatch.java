package io.datavortex.adinsight.advertising.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record LocalSettingsPatch(
        long expectedVersion,
        boolean replaceNote,
        String note,
        boolean replaceTags,
        List<String> tags) {
    public LocalSettingsPatch {
        if (expectedVersion < 1 || (!replaceNote && !replaceTags))
            throw new InvalidInputException(
                    "A version and at least one local setting are required");
        if (note != null && note.getBytes(StandardCharsets.UTF_8).length > 65535)
            throw new InvalidInputException("Local note exceeds MySQL TEXT capacity");
        if (replaceTags && (tags == null || tags.stream().anyMatch(java.util.Objects::isNull)))
            throw new InvalidInputException("Local tags must be strings");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
