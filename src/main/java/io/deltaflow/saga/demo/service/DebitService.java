package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DebitService {

    private static final Logger log = LoggerFactory.getLogger(DebitService.class);

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicInteger compensationCount = new AtomicInteger(0);
    private final Set<String> compensatedKeys = ConcurrentHashMap.newKeySet();

    public record DebitResult(String debitId, String accountId, double amount, String status) {}

    public DebitResult debit(String accountId, double amount, String idempotencyKey) {
        executionCount.incrementAndGet();
        log.info("Executing debit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
        if (amount > 1_000_000.0) {
            throw new IllegalArgumentException("Amount exceeds maximum debit limit");
        }
        String debitId = "deb_" + UUID.randomUUID().toString().substring(0, 8);
        return new DebitResult(debitId, accountId, amount, "SUCCESS");
    }

    public void compensateDebit(String accountId, double amount, String idempotencyKey) {
        compensationCount.incrementAndGet();
        if (idempotencyKey != null) {
            compensatedKeys.add(idempotencyKey);
        }
        log.info("Compensating debit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
    }

    public int getExecutionCount() {
        return executionCount.get();
    }

    public int getCompensationCount() {
        return compensationCount.get();
    }

    public boolean isCompensated(String idempotencyKey) {
        return compensatedKeys.contains(idempotencyKey);
    }

    public void reset() {
        executionCount.set(0);
        compensationCount.set(0);
        compensatedKeys.clear();
    }
}
