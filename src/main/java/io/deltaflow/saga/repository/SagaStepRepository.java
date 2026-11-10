package io.deltaflow.saga.repository;

import io.deltaflow.saga.domain.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, UUID> {

    List<SagaStep> findBySagaIdOrderByStepIndexAsc(UUID sagaId);

    Optional<SagaStep> findBySagaIdAndStepIndex(UUID sagaId, int stepIndex);

    Optional<SagaStep> findBySagaIdAndStepName(UUID sagaId, String stepName);
}

