package io.deltaflow.idempotency.repository;

import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.id.tenantId = :tenantId AND r.id.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyRecord> findForUpdate(
        @Param("tenantId") String tenantId, 
        @Param("idempotencyKey") String idempotencyKey
    );

    default Optional<IdempotencyRecord> findById(String tenantId, String idempotencyKey) {
        return findById(new IdempotencyRecordId(tenantId, idempotencyKey));
    }
}

