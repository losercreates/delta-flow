package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicInteger compensationCount = new AtomicInteger(0);

    public record CreditResult(String creditId, String accountId, double amount, String status) {}

    public CreditResult credit(String accountId, double amount, String idempotencyKey) {
        executionCount.incrementAndGet();
        log.info("Executing credit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
        if (accountId != null && (accountId.contains("frozen") || accountId.contains("error") || accountId.contains("fail"))) {
            throw new IllegalStateException("Target account frozen or credit rejected: " + accountId);
        }
        String creditId = "crd_" + UUID.randomUUID().toString().substring(0, 8);
        return new CreditResult(creditId, accountId, amount, "SUCCESS");
    }

    public void compensateCredit(String accountId, double amount, String idempotencyKey) {
        compensationCount.incrementAndGet();
        log.info("Compensating credit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
    }

    public int getExecutionCount() {
        return executionCount.get();
    }

    public int getCompensationCount() {
        return compensationCount.get();
    }

    public void reset() {
        executionCount.set(0);
        compensationCount.set(0);
    }
}
