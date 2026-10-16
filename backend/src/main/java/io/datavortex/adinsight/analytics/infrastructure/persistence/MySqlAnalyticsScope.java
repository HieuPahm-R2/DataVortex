package io.datavortex.adinsight.analytics.infrastructure.persistence;

import io.datavortex.adinsight.analytics.application.port.IAnalyticsScope;
import io.datavortex.adinsight.analytics.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MySqlAnalyticsScope implements IAnalyticsScope {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MySqlAnalyticsScope(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<AnalyticsAccount> resolve(UUID tenant, AnalyticsQuery query) {
        return transactions.execute(
                tx -> {
                    if (query.accountId() != null
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ad_accounts WHERE tenant_id=? AND id=?",
                                            Long.class,
                                            tenant.toString(),
                                            query.accountId().toString())
                                    != 1)
                        throw new BusinessException(
                                BusinessException.Code.NOT_FOUND, "Account not found");
                    if (query.campaignId() != null
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM campaigns WHERE tenant_id=? AND id=? AND is_deleted=FALSE",
                                            Long.class,
                                            tenant.toString(),
                                            query.campaignId().toString())
                                    != 1)
                        throw new BusinessException(
                                BusinessException.Code.NOT_FOUND, "Campaign not found");
                    String sql =
                            """
    SELECT a.id,a.currency,c.id AS campaign_id FROM ad_accounts a
    LEFT JOIN campaigns c ON c.tenant_id=a.tenant_id AND c.account_id=a.id AND c.is_deleted=FALSE
    WHERE a.tenant_id=?
    """;
                    List<Object> args = new ArrayList<>();
                    args.add(tenant.toString());
                    if (query.accountId() != null) {
                        sql += " AND a.id=?";
                        args.add(query.accountId().toString());
                    }
                    if (query.campaignId() != null) {
                        sql += " AND c.id=?";
                        args.add(query.campaignId().toString());
                    }
                    if (query.currency() != null) {
                        sql += " AND a.currency=?";
                        args.add(query.currency().getCurrencyCode());
                    }
                    var currencies = new LinkedHashMap<UUID, String>();
                    var campaigns = new LinkedHashMap<UUID, List<UUID>>();
                    jdbc.query(
                            sql + " ORDER BY a.id,c.id",
                            row -> {
                                UUID id = UUID.fromString(row.getString("id"));
                                currencies.putIfAbsent(id, row.getString("currency"));
                                campaigns.computeIfAbsent(id, key -> new ArrayList<>());
                                String campaign = row.getString("campaign_id");
                                if (campaign != null)
                                    campaigns.get(id).add(UUID.fromString(campaign));
                            },
                            args.toArray());
                    return currencies.entrySet().stream()
                            .map(
                                    entry ->
                                            new AnalyticsAccount(
                                                    entry.getKey(),
                                                    entry.getValue(),
                                                    campaigns.get(entry.getKey())))
                            .toList();
                });
    }
}
