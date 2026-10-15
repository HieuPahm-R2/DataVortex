package io.datavortex.adinsight.advertising.infrastructure.persistence;

import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.advertising.domain.model.SourcePlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "ad_accounts")
public class AdAccountEntity {
    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "tenant_id", columnDefinition = "char(36)", nullable = false)
    private String tenantId;

    private String platform;

    @Column(name = "source_account_id")
    private String sourceAccountId;

    @Column(columnDefinition = "char(3)")
    private String currency;

    private String timezone;
    private String status;

    protected AdAccountEntity() {}

    AdAccount toDomain() {
        return new AdAccount(
                UUID.fromString(id),
                UUID.fromString(tenantId),
                SourcePlatform.valueOf(platform),
                sourceAccountId,
                Currency.getInstance(currency),
                ZoneId.of(timezone),
                status);
    }
}
