package io.deltaflow.dashboard.service;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.dashboard.dto.OperatorInterventionRequest;
import io.deltaflow.dashboard.dto.OperatorInterventionResult;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class OperatorInterventionService {

    private static final Logger log = LoggerFactory.getLogger(OperatorInterventionService.class);
    private static final Duration LEASE_SAFETY_WINDOW = Duration.ofSeconds(5);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final AuditLogger auditLogger;
    private final DashboardEventBroadcaster eventBroadcaster;

    @Autowired
    public OperatorInterventionService(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            AuditLogger auditLogger,
            @Autowired(required = false) DashboardEventBroadcaster eventBroadcaster
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.auditLogger = auditLogger;
        this.eventBroadcaster = eventBroadcaster;
    }

    @Transactional
    public OperatorInterventionResult intervene(UUID sagaId, OperatorInterventionRequest request) {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(request, "Intervention request must not be null");

        SagaInstance instance = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saga not found: " + sagaId));

        String previousStatus = instance.getStatus();

        // 1. Guard against double-spend races: Check if saga is actively executing with a valid lease
        if ("EXECUTING".equals(previousStatus)) {
            Instant leaseExpiry = instance.getUpdatedAt().plus(LEASE_SAFETY_WINDOW);
            if (leaseExpiry.isAfter(Instant.now())) {
                log.warn("Operator intervention rejected for active saga [{}] with unexpired lease until {}", sagaId, leaseExpiry);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Target saga is actively executing with a valid lease until " + leaseExpiry
                );
            }
        }

        String action = request.action().trim().toUpperCase();
        String newStatus;
        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);

        if ("FORCE_COMPENSATE".equals(action)) {
            newStatus = "COMPENSATED";
            instance.setStatus(newStatus);
            instance.setResultPayload("{\"intervention\":\"FORCE_COMPENSATE\",\"operatorId\":\"" + request.operatorId() + "\"}");
            sagaInstanceRepository.save(instance);

            // Increment fencing tokens on steps to fence out zombie workers and mark steps as compensated
            for (SagaStep step : steps) {
                step.setFencingToken(step.getFencingToken() + 1);
                if (!"COMPLETED".equals(step.getStatus()) && !"COMPENSATED".equals(step.getStatus())) {
                    step.setStatus("COMPENSATED");
                }
                sagaStepRepository.save(step);
            }
        } else if ("FORCE_RETRY".equals(action)) {
            newStatus = "EXECUTING";
            instance.setStatus(newStatus);
            sagaInstanceRepository.save(instance);

            if (request.stepIndex() != null) {
                int targetStep = request.stepIndex();
                SagaStep step = steps.stream()
                        .filter(s -> s.getStepIndex() == targetStep)
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step index not found: " + targetStep));
                step.setFencingToken(step.getFencingToken() + 1);
                step.setStatus("EXECUTING");
                sagaStepRepository.save(step);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported intervention action: " + action);
        }

        // 2. Immutable audit log: OPERATOR_INTERVENTION
        String auditEventId = UUID.randomUUID().toString();
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("auditEventId", auditEventId);
        auditDetails.put("sagaId", sagaId.toString());
        auditDetails.put("action", action);
        auditDetails.put("previousStatus", previousStatus);
        auditDetails.put("newStatus", newStatus);
        auditDetails.put("operatorId", request.operatorId());
        auditDetails.put("justification", request.justification());
        if (request.stepIndex() != null) {
            auditDetails.put("stepIndex", request.stepIndex());
        }

        auditLogger.logEvent("OPERATOR_INTERVENTION", instance.getTenantId(), instance.getIdempotencyKey(), auditDetails);

        // 3. Real-time broadcast
        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("OPERATOR_INTERVENTION", sagaId, instance.getTenantId(), auditDetails);
        }

        log.info("Operator [{}] successfully applied intervention [{}] to saga [{}] ({} -> {})",
                request.operatorId(), action, sagaId, previousStatus, newStatus);

        return new OperatorInterventionResult(
                sagaId,
                action,
                previousStatus,
                newStatus,
                request.operatorId(),
                auditEventId,
                Instant.now()
        );
    }
}
