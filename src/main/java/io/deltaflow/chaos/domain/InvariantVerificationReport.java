package io.deltaflow.chaos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record InvariantVerificationReport(
        UUID reportId,
        Instant timestamp,
        String status,
        long totalTransactionsAudited,
        long successfulTransactions,
        long compensatedTransactions,
        long failedTransactions,
        BigDecimal totalSourceDebits,
        BigDecimal totalTargetCredits,
        BigDecimal totalRefundedCredits,
        BigDecimal discrepancyAmount,
        int doubleSpendCount,
        int orphanedDebitCount,
        List<InvariantViolation> violations
) {
    public static InvariantVerificationReport success(
            long total,
            long successful,
            long compensated,
            long failed,
            BigDecimal debits,
            BigDecimal credits,
            BigDecimal refunds
    ) {
        BigDecimal discrepancy = debits.subtract(credits.add(refunds));
        return new InvariantVerificationReport(
                UUID.randomUUID(),
                Instant.now(),
                "PASSED",
                total,
                successful,
                compensated,
                failed,
                debits,
                credits,
                refunds,
                discrepancy,
                0,
                0,
                Collections.emptyList()
        );
    }
}
