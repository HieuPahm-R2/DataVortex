package io.datavortex.adinsight.advertising.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

public interface SpringDataAccountRepository extends Repository<AdAccountEntity, String> {
    Slice<AdAccountEntity> findByTenantId(String tenantId, Pageable pageable);

    Optional<AdAccountEntity> findByTenantIdAndId(String tenantId, String id);
}
