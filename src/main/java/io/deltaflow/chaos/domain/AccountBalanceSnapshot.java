package io.deltaflow.chaos.domain;

import java.math.BigDecimal;

public record AccountBalanceSnapshot(
        String accountId,
        BigDecimal initialBalance,
        BigDecimal currentBalance,
        BigDecimal totalDebited,
        BigDecimal totalCredited,
        BigDecimal totalRefunded,
        boolean isConsistent
) {
    public static AccountBalanceSnapshot of(
            String accountId,
            BigDecimal initialBalance,
            BigDecimal currentBalance,
            BigDecimal totalDebited,
            BigDecimal totalCredited,
            BigDecimal totalRefunded
    ) {
        BigDecimal expected = initialBalance.subtract(totalDebited).add(totalCredited).add(totalRefunded);
        boolean consistent = expected.compareTo(currentBalance) == 0;
        return new AccountBalanceSnapshot(
                accountId,
                initialBalance,
                currentBalance,
                totalDebited,
                totalCredited,
                totalRefunded,
                consistent
        );
    }
}
