package io.datavortex.adinsight.advertising.infrastructure.persistence;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.advertising.application.port.IMetadataCatalog;
import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MySqlMetadataCatalog implements IMetadataCatalog {
    private static final String CAMPAIGN =
            """
 SELECT c.*,a.platform FROM campaigns c JOIN ad_accounts a ON a.tenant_id=c.tenant_id AND a.id=c.account_id
 WHERE c.tenant_id=? AND c.id=? AND c.is_deleted=FALSE
 """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public MySqlMetadataCatalog(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public Optional<Campaign> campaign(UUID tenantId, UUID id) {
        return findCampaign(tenantId, id, false);
    }

    private Optional<Campaign> findCampaign(UUID tenantId, UUID id, boolean lock) {
        return jdbc
                .query(
                        CAMPAIGN + (lock ? " FOR UPDATE" : ""),
                        this::campaignRow,
                        tenantId.toString(),
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Keyword> keyword(UUID tenantId, UUID id) {
        return jdbc
                .query(
                        """
   SELECT k.*,c.account_id,a.platform,a.currency FROM keywords k
   JOIN ad_groups g ON g.tenant_id=k.tenant_id AND g.id=k.ad_group_id AND g.campaign_id=k.campaign_id
   JOIN campaigns c ON c.tenant_id=k.tenant_id AND c.id=k.campaign_id
   JOIN ad_accounts a ON a.tenant_id=c.tenant_id AND a.id=c.account_id
   WHERE k.tenant_id=? AND k.id=? AND k.is_deleted=FALSE AND g.is_deleted=FALSE AND c.is_deleted=FALSE
   """,
                        (row, index) ->
                                new Keyword(
                                        uuid(row, "id"),
                                        uuid(row, "tenant_id"),
                                        uuid(row, "account_id"),
                                        uuid(row, "campaign_id"),
                                        uuid(row, "ad_group_id"),
                                        SourcePlatform.valueOf(row.getString("platform")),
                                        row.getString("source_keyword_id"),
                                        row.getString("text"),
                                        row.getString("match_type"),
                                        row.getString("status"),
                                        row.getLong("bid_micros"),
                                        Currency.getInstance(row.getString("currency")),
                                        row.getLong("search_version"),
                                        row.getTimestamp("updated_at").toInstant()),
                        tenantId.toString(),
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Campaign patch(TenantScope scope, UUID id, LocalSettingsPatch patch, UUID requestId) {
        return transactions.execute(
                tx -> {
                    Campaign before =
                            findCampaign(scope.tenantId(), id, true)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            NOT_FOUND, "Campaign not found"));
                    if (before.searchVersion() != patch.expectedVersion())
                        throw new BusinessException(
                                OPTIMISTIC_CONFLICT,
                                "Campaign version changed; reload before retrying");
                    String note = patch.replaceNote() ? patch.note() : before.localNote();
                    List<String> tags = patch.replaceTags() ? patch.tags() : before.localTags();
                    if (Objects.equals(note, before.localNote()) && tags.equals(before.localTags()))
                        return before;
                    int updated =
                            jdbc.update(
                                    """
    UPDATE campaigns SET local_note=?,local_tags=?,search_version=search_version+1,updated_at=CURRENT_TIMESTAMP(6)
    WHERE tenant_id=? AND id=? AND search_version=? AND is_deleted=FALSE
    """,
                                    note,
                                    encode(tags),
                                    scope.tenantId().toString(),
                                    id.toString(),
                                    patch.expectedVersion());
                    if (updated != 1)
                        throw new BusinessException(
                                OPTIMISTIC_CONFLICT,
                                "Campaign version changed; reload before retrying");
                    Campaign after = findCampaign(scope.tenantId(), id, false).orElseThrow();
                    jdbc.update(
                            """
    INSERT INTO outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload)
    VALUES (?,?,'CAMPAIGN',?,?,'UPSERT',1,?)
    """,
                            UUID.randomUUID().toString(),
                            scope.tenantId().toString(),
                            id.toString(),
                            after.searchVersion(),
                            encode(projection(after)));
                    audit(
                            scope,
                            "CAMPAIGN_LOCAL_SETTINGS_UPDATED",
                            "CAMPAIGN",
                            id,
                            settings(before),
                            settings(after),
                            requestId);
                    return after;
                });
    }

    @Override
    public AdAccount createAccount(TenantScope scope, NewAccount account, UUID requestId) {
        try {
            return transactions.execute(
                    tx -> {
                        UUID id = UUID.randomUUID();
                        jdbc.update(
                                """
     INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,credential_ref,status)
     VALUES (?,?,?,?,?,?,?,'ACTIVE')
     """,
                                id.toString(),
                                scope.tenantId().toString(),
                                account.platform().name(),
                                account.sourceAccountId(),
                                account.currency().getCurrencyCode(),
                                account.timezone().getId(),
                                account.credentialRef());
                        AdAccount created =
                                new AdAccount(
                                        id,
                                        scope.tenantId(),
                                        account.platform(),
                                        account.sourceAccountId(),
                                        account.currency(),
                                        account.timezone(),
                                        "ACTIVE");
                        audit(
                                scope,
                                "ACCOUNT_CREATED",
                                "AD_ACCOUNT",
                                id,
                                null,
                                Map.of(
                                        "platform",
                                        account.platform(),
                                        "sourceAccountId",
                                        account.sourceAccountId(),
                                        "currency",
                                        account.currency().getCurrencyCode(),
                                        "timezone",
                                        account.timezone().getId()),
                                requestId);
                        return created;
                    });
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(
                    RESOURCE_CONFLICT,
                    "An account with that platform and source ID already exists in this tenant");
        }
    }

    private Campaign campaignRow(ResultSet row, int index) throws SQLException {
        return new Campaign(
                uuid(row, "id"),
                uuid(row, "tenant_id"),
                uuid(row, "account_id"),
                SourcePlatform.valueOf(row.getString("platform")),
                row.getString("source_campaign_id"),
                row.getString("name"),
                row.getString("source_status"),
                row.getString("local_note"),
                tags(row.getString("local_tags")),
                date(row, "start_date"),
                date(row, "end_date"),
                row.getLong("search_version"),
                row.getTimestamp("updated_at").toInstant());
    }

    private List<String> tags(String value) {
        try {
            var array = json.readTree(value);
            if (!array.isArray())
                throw new IllegalStateException("Invalid persisted campaign tags");
            List<String> tags = new ArrayList<>();
            for (var tag : array) {
                if (!tag.isTextual())
                    throw new IllegalStateException("Invalid persisted campaign tags");
                tags.add(tag.textValue());
            }
            return tags;
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid persisted campaign tags");
        }
    }

    private static Map<String, Object> projection(Campaign campaign) {
        return Map.ofEntries(
                Map.entry("document_id", campaign.tenantId() + ":" + campaign.id()),
                Map.entry("tenant_id", campaign.tenantId().toString()),
                Map.entry("entity_type", "CAMPAIGN"),
                Map.entry("platform", campaign.platform().name()),
                Map.entry("account_id", campaign.accountId().toString()),
                Map.entry("campaign_id", campaign.id().toString()),
                Map.entry("title", campaign.name()),
                Map.entry("autocomplete", campaign.name()),
                Map.entry("status", campaign.sourceStatus()),
                Map.entry("tags", campaign.localTags()),
                Map.entry("is_deleted", false),
                Map.entry("search_version", campaign.searchVersion()),
                Map.entry("updated_at", campaign.updatedAt().toString()));
    }

    private static Map<String, Object> settings(Campaign campaign) {
        // Audit records changed values; no credential reference or provider token is selected.
        var value = new LinkedHashMap<String, Object>();
        value.put("localNote", campaign.localNote());
        value.put("localTags", campaign.localTags());
        value.put("searchVersion", campaign.searchVersion());
        return value;
    }

    private void audit(
            TenantScope scope,
            String action,
            String type,
            UUID id,
            Object before,
            Object after,
            UUID requestId) {
        jdbc.update(
                """
   INSERT INTO audit_logs(id,tenant_id,actor_id,action,entity_type,entity_id,before_data,after_data,correlation_id)
   VALUES (?,?,?,?,?,?,?,?,?)
   """,
                UUID.randomUUID().toString(),
                scope.tenantId().toString(),
                scope.userId(),
                action,
                type,
                id.toString(),
                before == null ? null : encode(before),
                encode(after),
                requestId.toString());
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to encode metadata");
        }
    }

    private static UUID uuid(ResultSet row, String key) throws SQLException {
        return UUID.fromString(row.getString(key));
    }

    private static LocalDate date(ResultSet row, String key) throws SQLException {
        var date = row.getDate(key);
        return date == null ? null : date.toLocalDate();
    }
}
