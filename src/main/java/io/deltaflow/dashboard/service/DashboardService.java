package io.deltaflow.dashboard.service;

import io.deltaflow.dashboard.dto.*;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public DashboardService(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Transactional(readOnly = true)
    public List<SagaSummaryDto> listSagas(String status, String tenantId, int page, int size) {
        String filterStatus = (status != null && !status.isBlank()) ? status.trim() : null;
        String filterTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : null;
        int pageIndex = Math.max(0, page);
        int pageSize = (size > 0 && size <= 100) ? size : 20;

        return sagaInstanceRepository.searchSagas(filterStatus, filterTenant, PageRequest.of(pageIndex, pageSize))
                .stream()
                .map(SagaSummaryDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<SagaGraphDto> getSagaGraph(UUID sagaId) {
        Optional<SagaInstance> instanceOpt = sagaInstanceRepository.findById(sagaId);
        if (instanceOpt.isEmpty()) {
            return Optional.empty();
        }

        SagaInstance instance = instanceOpt.get();
        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);

        List<SagaNodeDto> nodes = new ArrayList<>();
        for (SagaStep step : steps) {
            boolean isParallel = step.getStepName() != null &&
                    (step.getStepName().toLowerCase().contains("parallel") || step.getStepName().toLowerCase().contains("branch"));
            nodes.add(new SagaNodeDto(
                    "step-" + step.getStepIndex(),
                    step.getStepIndex(),
                    step.getStepName(),
                    step.getStatus(),
                    isParallel,
                    isParallel ? step.getStepName() : null,
                    step.getFencingToken(),
                    step.getRequestPayload(),
                    step.getResponsePayload(),
                    step.getErrorDetails(),
                    step.getCreatedAt(),
                    step.getUpdatedAt()
            ));
        }

        List<SagaEdgeDto> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            SagaNodeDto current = nodes.get(i);
            SagaNodeDto next = nodes.get(i + 1);
            boolean animated = "EXECUTING".equals(current.status()) || "EXECUTING".equals(next.status()) ||
                    "COMPENSATING".equals(current.status()) || "COMPENSATING".equals(next.status());
            String edgeType = "COMPENSATED".equals(next.status()) || "COMPENSATING".equals(next.status())
                    ? "COMPENSATION"
                    : "FORWARD";
            edges.add(new SagaEdgeDto(
                    "edge-" + current.id() + "-to-" + next.id(),
                    current.id(),
                    next.id(),
                    edgeType,
                    animated
            ));
        }

        return Optional.of(new SagaGraphDto(
                instance.getSagaId(),
                instance.getSagaType(),
                instance.getStatus(),
                instance.getTenantId(),
                instance.getIdempotencyKey(),
                instance.getPayload(),
                instance.getCurrentStep(),
                instance.getCreatedAt(),
                instance.getUpdatedAt(),
                nodes,
                edges
        ));
    }

    @Transactional(readOnly = true)
    public List<IdempotencyForensicDto> searchIdempotencyRecords(
            String key,
            String payloadHash,
            String tenantId,
            String status
    ) {
        List<IdempotencyRecord> allRecords = idempotencyRecordRepository.findAll();
        return allRecords.stream()
                .filter(r -> key == null || key.isBlank() || (r.getId() != null && r.getId().getIdempotencyKey().contains(key.trim())))
                .filter(r -> payloadHash == null || payloadHash.isBlank() || (r.getRequestHash() != null && r.getRequestHash().equalsIgnoreCase(payloadHash.trim())))
                .filter(r -> tenantId == null || tenantId.isBlank() || (r.getId() != null && r.getId().getTenantId().equalsIgnoreCase(tenantId.trim())))
                .filter(r -> status == null || status.isBlank() || (r.getStatus() != null && r.getStatus().equalsIgnoreCase(status.trim())))
                .map(IdempotencyForensicDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TelemetrySummaryDto getTelemetry() {
        long activeSagas = sagaInstanceRepository.countByStatus("EXECUTING");
        long completedSagas = sagaInstanceRepository.countByStatus("COMPLETED");
        long compensatedSagas = sagaInstanceRepository.countByStatus("COMPENSATED");
        long failedSagas = sagaInstanceRepository.countByStatus("FAILED");

        long pendingOutbox = outboxEventRepository.countByStatus("PENDING");
        long deadLetters = outboxEventRepository.countByStatus("DEAD_LETTER");

        List<CircuitBreakerStatusDto> cbList = new ArrayList<>();
        if (circuitBreakerRegistry != null) {
            for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
                cbList.add(new CircuitBreakerStatusDto(
                        cb.getName(),
                        cb.getState().name(),
                        cb.getMetrics().getFailureRate(),
                        cb.getMetrics().getNumberOfBufferedCalls()
                ));
            }
        }

        return new TelemetrySummaryDto(
                activeSagas,
                completedSagas,
                compensatedSagas,
                failedSagas,
                pendingOutbox,
                deadLetters,
                cbList
        );
    }
}
