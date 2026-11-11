package io.deltaflow.saga.repository;

import io.deltaflow.saga.domain.SagaInstance;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Optional<SagaInstance> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SagaInstance s WHERE s.tenantId = :tenantId AND s.idempotencyKey = :idempotencyKey")
    Optional<SagaInstance> findForUpdate(@Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SagaInstance s WHERE s.sagaId = :sagaId")
    Optional<SagaInstance> findByIdForUpdate(@Param("sagaId") UUID sagaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
        SELECT s FROM SagaInstance s 
        WHERE s.status IN ('EXECUTING', 'COMPENSATING') 
          AND s.updatedAt < :threshold
        ORDER BY s.updatedAt ASC
    """)
    List<SagaInstance> findExpiredInFlightForUpdate(@Param("threshold") Instant threshold);
}
