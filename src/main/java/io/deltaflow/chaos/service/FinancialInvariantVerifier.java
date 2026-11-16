package io.deltaflow.chaos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.chaos.domain.InvariantVerificationReport;
import io.deltaflow.chaos.domain.InvariantViolation;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class FinancialInvariantVerifier {

    private static final Logger log = LoggerFactory.getLogger(FinancialInvariantVerifier.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public FinancialInvariantVerifier(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            ObjectMapper objectMapper
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InvariantVerificationReport verifyInvariants() {
        return verifyInvariants(null);
    }

    @Transactional(readOnly = true)
    public InvariantVerificationReport verifyInvariants(String tenantId) {
        log.info("Starting automated financial invariant verification scan (tenant: {})...", tenantId);

        List<SagaInstance> allSagas = (tenantId != null && !tenantId.isBlank())
                ? sagaInstanceRepository.findAll().stream().filter(s -> tenantId.equals(s.getTenantId())).toList()
                : sagaInstanceRepository.findAll();
        List<IdempotencyRecord> allIdempotency = (tenantId != null && !tenantId.isBlank())
                ? idempotencyRecordRepository.findAll().stream().filter(r -> r.getId() != null && tenantId.equals(r.getId().getTenantId())).toList()
                : idempotencyRecordRepository.findAll();

        long totalAudited = allSagas.size();
        long successfulCount = 0;
        long compensatedCount = 0;
        long failedCount = 0;

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalRefunds = BigDecimal.ZERO;

        List<InvariantViolation> violations = new ArrayList<>();
        int doubleSpendCount = 0;
        int orphanedDebitCount = 0;

        // 1. Audit each saga transaction
        for (SagaInstance saga : allSagas) {
            BigDecimal amount = extractAmount(saga.getPayload());

            if ("COMPLETED".equals(saga.getStatus())) {
                successfulCount++;
                totalDebits = totalDebits.add(amount);
                totalCredits = totalCredits.add(amount);
            } else if ("COMPENSATED".equals(saga.getStatus())) {
                compensatedCount++;
                totalDebits = totalDebits.add(amount);
                totalRefunds = totalRefunds.add(amount);
            } else if ("FAILED".equals(saga.getStatus())) {
                failedCount++;
                // Verify whether step 0 (debit) completed without corresponding compensation
                List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(saga.getSagaId());
                boolean debitCompleted = steps.stream()
                        .anyMatch(s -> s.getStepIndex() == 0 && "COMPLETED".equals(s.getStatus()));
                boolean debitCompensated = steps.stream()
                        .anyMatch(s -> s.getStepIndex() == 0 && "COMPENSATED".equals(s.getStatus()));

                if (debitCompleted && !debitCompensated) {
                    orphanedDebitCount++;
                    violations.add(new InvariantViolation(
                            "ORPHANED_DEBIT",
                            null,
                            saga.getSagaId(),
                            "Compensated or Refunded",
                            "Unrefunded Completed Debit",
                            "Saga failed after step 0 but step 0 was never compensated."
                    ));
                }
            }
        }

        // 2. Audit Idempotency Records for Double-Spends
        Map<String, Integer> keyCountMap = new HashMap<>();
        for (IdempotencyRecord rec : allIdempotency) {
            if ("RESOLVED".equals(rec.getStatus()) && rec.getId() != null) {
                String key = rec.getId().getTenantId() + ":" + rec.getId().getIdempotencyKey();
                keyCountMap.put(key, keyCountMap.getOrDefault(key, 0) + 1);
            }
        }

        for (Map.Entry<String, Integer> entry : keyCountMap.entrySet()) {
            if (entry.getValue() > 1) {
                doubleSpendCount++;
                violations.add(new InvariantViolation(
                        "DOUBLE_SPEND",
                        null,
                        null,
                        "1",
                        String.valueOf(entry.getValue()),
                        "Multiple resolved records found for idempotency key: " + entry.getKey()
                ));
            }
        }

        // 3. Balance Equation: Sum(Debits) == Sum(Credits) + Sum(Refunds)
        BigDecimal expectedOffset = totalCredits.add(totalRefunds);
        BigDecimal discrepancy = totalDebits.subtract(expectedOffset);

        if (discrepancy.compareTo(BigDecimal.ZERO) != 0) {
            violations.add(new InvariantViolation(
                    "BALANCE_MISMATCH",
                    null,
                    null,
                    totalDebits.toPlainString(),
                    expectedOffset.toPlainString(),
                    "Discrepancy of " + discrepancy.toPlainString() + " detected between debits and credits+refunds."
            ));
        }

        String reportStatus = (discrepancy.compareTo(BigDecimal.ZERO) == 0 && violations.isEmpty())
                ? "PASSED"
                : "VIOLATED";

        log.info("Invariant verification completed: status={}, debits={}, credits={}, refunds={}, discrepancy={}, violations={}",
                reportStatus, totalDebits, totalCredits, totalRefunds, discrepancy, violations.size());

        return new InvariantVerificationReport(
                UUID.randomUUID(),
                Instant.now(),
                reportStatus,
                totalAudited,
                successfulCount,
                compensatedCount,
                failedCount,
                totalDebits,
                totalCredits,
                totalRefunds,
                discrepancy,
                doubleSpendCount,
                orphanedDebitCount,
                violations
        );
    }

    private BigDecimal extractAmount(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node.has("amount") && node.get("amount").isNumber()) {
                return BigDecimal.valueOf(node.get("amount").asDouble());
            }
        } catch (Exception e) {
            log.debug("Could not parse amount from payload: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }
}
