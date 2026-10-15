package io.datavortex.adinsight.advertising.infrastructure.persistence;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaAccountCatalog implements AccountCatalog {
    private final SpringDataAccountRepository repository;

    public JpaAccountCatalog(SpringDataAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AdAccount> list(UUID tenantId) {
        return repository.findByTenantIdOrderByIdAsc(tenantId.toString()).stream()
                .map(AdAccountEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<AdAccount> find(UUID tenantId, UUID accountId) {
        return repository
                .findByTenantIdAndId(tenantId.toString(), accountId.toString())
                .map(AdAccountEntity::toDomain);
    }
}
