package io.datavortex.adinsight.budgeting.infrastructure.persistence;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.budgeting.application.port.IBudgetPlans;
import io.datavortex.adinsight.budgeting.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MySqlBudgetPlans implements IBudgetPlans {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public MySqlBudgetPlans(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public Optional<BudgetPlan> find(UUID tenant, UUID campaign, LocalDate start, LocalDate end) {
        return transactions.execute(tx -> find(tenant, campaign, start, end, false));
    }

    private Optional<BudgetPlan> find(
            UUID tenant, UUID campaign, LocalDate start, LocalDate end, boolean lock) {
        var values =
                jdbc.query(
                        """
   SELECT b.* FROM budget_plans b JOIN campaigns c ON c.tenant_id=b.tenant_id AND c.id=b.campaign_id
   WHERE b.tenant_id=? AND b.campaign_id=? AND b.period_start=? AND b.period_end=? AND c.is_deleted=FALSE
   """
                                + (lock ? " FOR UPDATE" : ""),
                        (row, index) ->
                                new BudgetPlan(
                                        UUID.fromString(row.getString("id")),
                                        tenant,
                                        campaign,
                                        row.getDate("period_start").toLocalDate(),
                                        row.getDate("period_end").toLocalDate(),
                                        row.getBigDecimal("budget_amount"),
                                        Currency.getInstance(row.getString("currency")),
                                        row.getLong("revision"),
                                        row.getBoolean("active"),
                                        List.of(),
                                        row.getTimestamp("updated_at").toInstant()),
                        tenant.toString(),
                        campaign.toString(),
                        start,
                        end);
        if (values.isEmpty()) return Optional.empty();
        var plan = values.getFirst();
        var targets =
                jdbc.query(
                        """
   SELECT metric,operator,target_value,min_samples FROM kpi_targets WHERE tenant_id=? AND budget_plan_id=? ORDER BY metric,operator,id
   """,
                        (row, index) ->
                                new KpiTarget(
                                        KpiMetric.valueOf(row.getString("metric")),
                                        KpiOperator.valueOf(row.getString("operator")),
                                        row.getBigDecimal("target_value"),
                                        row.getLong("min_samples")),
                        tenant.toString(),
                        plan.id().toString());
        return Optional.of(
                new BudgetPlan(
                        plan.id(),
                        tenant,
                        campaign,
                        start,
                        end,
                        plan.amount(),
                        plan.currency(),
                        plan.revision(),
                        plan.active(),
                        targets,
                        plan.updatedAt()));
    }

    @Override
    public BudgetPlan save(TenantScope scope, UUID campaign, BudgetChange change, UUID requestId) {
        return transactions.execute(
                tx -> {
                    // Every budget writer locks its owning campaign, including when no plan row
                    // exists yet.
                    var currencies =
                            jdbc.queryForList(
                                    """
    SELECT a.currency FROM campaigns c JOIN ad_accounts a ON a.tenant_id=c.tenant_id AND a.id=c.account_id
    WHERE c.tenant_id=? AND c.id=? AND c.is_deleted=FALSE FOR UPDATE
    """,
                                    String.class,
                                    scope.tenantId().toString(),
                                    campaign.toString());
                    if (currencies.isEmpty())
                        throw new BusinessException(NOT_FOUND, "Campaign not found");
                    if (!currencies.getFirst().equals(change.currency().getCurrencyCode()))
                        throw new InvalidInputException(
                                "Budget currency must match the campaign account");
                    var existing =
                            find(
                                    scope.tenantId(),
                                    campaign,
                                    change.periodStart(),
                                    change.periodEnd(),
                                    true);
                    long revision = existing.map(BudgetPlan::revision).orElse(0L);
                    if (revision != change.expectedRevision())
                        throw new BusinessException(
                                OPTIMISTIC_CONFLICT,
                                "Budget revision changed; reload before retrying");
                    UUID id = existing.map(BudgetPlan::id).orElseGet(UUID::randomUUID);
                    if (change.active()) {
                        Long overlaps =
                                jdbc.queryForObject(
                                        """
     SELECT COUNT(*) FROM budget_plans WHERE tenant_id=? AND campaign_id=? AND active=TRUE AND id<>?
       AND period_start<=? AND period_end>=?
     """,
                                        Long.class,
                                        scope.tenantId().toString(),
                                        campaign.toString(),
                                        id.toString(),
                                        change.periodEnd(),
                                        change.periodStart());
                        if (overlaps != null && overlaps > 0)
                            throw new BusinessException(
                                    BUDGET_PERIOD_CONFLICT,
                                    "Active budget periods must not overlap");
                    }
                    if (existing.isEmpty())
                        jdbc.update(
                                """
    INSERT INTO budget_plans(id,tenant_id,campaign_id,period_start,period_end,budget_amount,currency,revision,active)
    VALUES (?,?,?,?,?,?,?,1,?)
    """,
                                id.toString(),
                                scope.tenantId().toString(),
                                campaign.toString(),
                                change.periodStart(),
                                change.periodEnd(),
                                change.amount(),
                                change.currency().getCurrencyCode(),
                                change.active());
                    else {
                        int updated =
                                jdbc.update(
                                        """
     UPDATE budget_plans SET budget_amount=?,currency=?,active=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6)
     WHERE tenant_id=? AND id=? AND revision=?
     """,
                                        change.amount(),
                                        change.currency().getCurrencyCode(),
                                        change.active(),
                                        scope.tenantId().toString(),
                                        id.toString(),
                                        change.expectedRevision());
                        if (updated != 1)
                            throw new BusinessException(
                                    OPTIMISTIC_CONFLICT,
                                    "Budget revision changed; reload before retrying");
                    }
                    jdbc.update(
                            "DELETE FROM kpi_targets WHERE tenant_id=? AND budget_plan_id=?",
                            scope.tenantId().toString(),
                            id.toString());
                    for (var target : change.kpiTargets())
                        jdbc.update(
                                """
    INSERT INTO kpi_targets(id,tenant_id,budget_plan_id,metric,operator,target_value,min_samples) VALUES (?,?,?,?,?,?,?)
    """,
                                UUID.randomUUID().toString(),
                                scope.tenantId().toString(),
                                id.toString(),
                                target.metric().name(),
                                target.operator().name(),
                                target.targetValue(),
                                target.minSamples());
                    BudgetPlan after =
                            find(
                                            scope.tenantId(),
                                            campaign,
                                            change.periodStart(),
                                            change.periodEnd(),
                                            false)
                                    .orElseThrow();
                    jdbc.update(
                            """
    INSERT INTO audit_logs(id,tenant_id,actor_id,action,entity_type,entity_id,before_data,after_data,correlation_id)
    VALUES (?,?,?,'BUDGET_SAVED','BUDGET_PLAN',?,?,?,?)
    """,
                            UUID.randomUUID().toString(),
                            scope.tenantId().toString(),
                            scope.userId(),
                            id.toString(),
                            existing.map(this::encode).orElse(null),
                            encode(after),
                            requestId.toString());
                    return after;
                });
    }

    private String encode(BudgetPlan plan) {
        try {
            return json.writeValueAsString(
                    Map.of(
                            "id",
                            plan.id().toString(),
                            "amount",
                            plan.amount().toPlainString(),
                            "currency",
                            plan.currency().getCurrencyCode(),
                            "revision",
                            plan.revision(),
                            "active",
                            plan.active(),
                            "periodStart",
                            plan.periodStart().toString(),
                            "periodEnd",
                            plan.periodEnd().toString(),
                            "kpiTargets",
                            plan.kpiTargets().stream()
                                    .map(
                                            t ->
                                                    Map.of(
                                                            "metric",
                                                            t.metric().name(),
                                                            "operator",
                                                            t.operator().name(),
                                                            "targetValue",
                                                            t.targetValue().toPlainString(),
                                                            "minSamples",
                                                            t.minSamples()))
                                    .toList()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to encode budget audit");
        }
    }
}
