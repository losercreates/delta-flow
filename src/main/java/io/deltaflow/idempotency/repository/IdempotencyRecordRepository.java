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

    @Query("SELECT MIN(r.createdAt) FROM IdempotencyRecord r WHERE r.status = 'RESOLVED'")
    Optional<java.time.Instant> findOldestResolvedRecordCreatedAt();

    @Query("SELECT COUNT(r) FROM IdempotencyRecord r WHERE r.status = 'RESOLVED' AND r.createdAt < :cutoff")
    long countExpiredResolvedRecords(@Param("cutoff") java.time.Instant cutoff);

    @Query("""
        SELECT r FROM IdempotencyRecord r 
        WHERE r.status = 'RESOLVED' AND r.createdAt < :cutoff 
        ORDER BY r.createdAt ASC
    """)
    java.util.List<IdempotencyRecord> findExpiredResolvedRecords(
        @Param("cutoff") java.time.Instant cutoff, 
        org.springframework.data.domain.Pageable pageable
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        DELETE FROM idempotency_records 
        WHERE (tenant_id, idempotency_key) IN (
            SELECT tenant_id, idempotency_key 
            FROM idempotency_records 
            WHERE status = 'RESOLVED' AND created_at < :cutoff 
            LIMIT :batchSize
        )
    """, nativeQuery = true)
    int deleteExpiredResolvedBatch(@Param("cutoff") java.time.Instant cutoff, @Param("batchSize") int batchSize);
}

