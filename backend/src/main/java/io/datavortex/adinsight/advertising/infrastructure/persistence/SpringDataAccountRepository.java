package io.datavortex.adinsight.advertising.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface SpringDataAccountRepository extends Repository<AdAccountEntity, String> {
    List<AdAccountEntity> findByTenantIdOrderByIdAsc(String tenantId);

    Optional<AdAccountEntity> findByTenantIdAndId(String tenantId, String id);
}
