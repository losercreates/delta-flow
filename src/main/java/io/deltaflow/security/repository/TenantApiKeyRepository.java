package io.deltaflow.security.repository;

import io.deltaflow.security.domain.TenantApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantApiKeyRepository extends JpaRepository<TenantApiKey, String> {

    Optional<TenantApiKey> findByHashedKeyAndEnabledTrue(String hashedKey);

    Optional<TenantApiKey> findByKeyIdAndEnabledTrue(String keyId);
}
