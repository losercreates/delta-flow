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
    private final DashboardEventBroadcaster eventBroadcaster;

    @Autowired
    public DashboardService(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry,
            @Autowired(required = false) DashboardEventBroadcaster eventBroadcaster
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventBroadcaster = eventBroadcaster;
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
                    step.getAwaitingEventName(),
                    step.getStepDeadline(),
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

    @Transactional(readOnly = true)
    public List<DeadLetterOutboxEventDto> listDeadLetterEvents(int page, int size) {
        List<io.deltaflow.outbox.domain.OutboxEvent> deadLetters = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                "DEAD_LETTER", PageRequest.of(page, size)
        );
        return deadLetters.stream()
                .map(e -> {
                    UUID sagaId = null;
                    if ("SAGA_INSTANCE".equals(e.getAggregateType()) || "SAGA_STEP".equals(e.getAggregateType())) {
                        try {
                            String aggId = e.getAggregateId();
                            if (aggId.contains(":")) {
                                aggId = aggId.substring(0, aggId.indexOf(':'));
                            }
                            sagaId = UUID.fromString(aggId);
                        } catch (Exception ignored) {}
                    }
                    return new DeadLetterOutboxEventDto(
                            e.getEventId(),
                            sagaId,
                            e.getPartitionKey(),
                            e.getEventType(),
                            "domain.events",
                            e.getPartitionKey(),
                            e.getPayload(),
                            e.getRetryCount(),
                            e.getErrorDetails(),
                            e.getCreatedAt(),
                            e.getPublishedAt() != null ? e.getPublishedAt() : e.getCreatedAt()
                    );
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public DeadLetterOutboxEventDto replayDeadLetterEvent(UUID eventId, DlqReplayRequest request) {
        io.deltaflow.outbox.domain.OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));

        if (!"DEAD_LETTER".equals(event.getStatus())) {
            throw new IllegalStateException("Event " + eventId + " is not in DEAD_LETTER status (status: " + event.getStatus() + ")");
        }

        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setErrorDetails(null);
        io.deltaflow.outbox.domain.OutboxEvent saved = outboxEventRepository.save(event);

        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("OUTBOX_REPLAYED", null, saved.getPartitionKey(), java.util.Map.of(
                    "eventId", saved.getEventId().toString(),
                    "operatorId", request.operatorId(),
                    "justification", request.justification()
            ));
        }

        UUID sagaId = null;
        try {
            String aggId = saved.getAggregateId();
            if (aggId.contains(":")) {
                aggId = aggId.substring(0, aggId.indexOf(':'));
            }
            sagaId = UUID.fromString(aggId);
        } catch (Exception ignored) {}

        return new DeadLetterOutboxEventDto(
                saved.getEventId(),
                sagaId,
                saved.getPartitionKey(),
                saved.getEventType(),
                "domain.events",
                saved.getPartitionKey(),
                saved.getPayload(),
                saved.getRetryCount(),
                saved.getErrorDetails(),
                saved.getCreatedAt(),
                saved.getPublishedAt() != null ? saved.getPublishedAt() : saved.getCreatedAt()
        );
    }
}
