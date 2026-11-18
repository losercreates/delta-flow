package io.deltaflow.saga.repository;

import io.deltaflow.saga.domain.WebhookIngressRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookIngressRepository extends JpaRepository<WebhookIngressRecord, UUID> {

    Optional<WebhookIngressRecord> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<WebhookIngressRecord> findBySagaIdOrderByCreatedAtAsc(UUID sagaId);
}

