package io.datavortex.adinsight.advertising.infrastructure.persistence;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AccountPage;
import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.shared.domain.PageQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class JpaAccountCatalog implements IAccountCatalog {
    private final SpringDataAccountRepository repository;

    public JpaAccountCatalog(SpringDataAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public AccountPage list(UUID tenantId, PageQuery page) {
        var rows =
                repository.findByTenantId(
                        tenantId.toString(),
                        PageRequest.of(page.page(), page.size(), Sort.by("id").ascending()));
        return new AccountPage(
                rows.stream().map(AdAccountEntity::toDomain).toList(),
                page.page(),
                page.size(),
                rows.hasNext());
    }

    @Override
    public Optional<AdAccount> find(UUID tenantId, UUID accountId) {
        return repository
                .findByTenantIdAndId(tenantId.toString(), accountId.toString())
                .map(AdAccountEntity::toDomain);
    }
}
