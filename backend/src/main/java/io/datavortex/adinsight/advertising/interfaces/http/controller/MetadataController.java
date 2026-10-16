package io.datavortex.adinsight.advertising.interfaces.http.controller;

import io.datavortex.adinsight.advertising.application.service.MetadataService;
import io.datavortex.adinsight.advertising.interfaces.http.dto.*;
import io.datavortex.adinsight.advertising.interfaces.http.mapper.MetadataResponseMapper;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetadataController {
    private final MetadataService metadata;

    public MetadataController(MetadataService metadata) {
        this.metadata = metadata;
    }

    @GetMapping("/campaigns/{id}")
    public CampaignResponse campaign(@PathVariable UUID id, HttpServletRequest request) {
        return MetadataResponseMapper.from(
                metadata.campaign(scope(request), id), requestId(request));
    }

    @GetMapping("/keywords/{id}")
    public KeywordResponse keyword(@PathVariable UUID id, HttpServletRequest request) {
        return MetadataResponseMapper.from(
                metadata.keyword(scope(request), id), requestId(request));
    }

    @PatchMapping("/campaigns/{id}/local-settings")
    public CampaignResponse patch(
            @PathVariable UUID id,
            @Valid @RequestBody PatchLocalSettingsRequest body,
            HttpServletRequest request) {
        return MetadataResponseMapper.from(
                metadata.patch(
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
