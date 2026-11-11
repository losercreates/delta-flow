package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicInteger compensationCount = new AtomicInteger(0);

    public record LedgerResult(String entryId, String debitRef, String creditRef, double amount, String currency, String status) {}

    public LedgerResult recordEntry(String debitRef, String creditRef, double amount, String currency) {
        executionCount.incrementAndGet();
        log.info("Recording double-entry ledger: debitRef={}, creditRef={}, amount={}, currency={}", debitRef, creditRef, amount, currency);
        String entryId = "led_" + UUID.randomUUID().toString().substring(0, 8);
        return new LedgerResult(entryId, debitRef, creditRef, amount, currency, "RECORDED");
    }

    public void compensateLedger(String debitRef, String creditRef, double amount, String currency) {
        compensationCount.incrementAndGet();
        log.info("Compensating (voiding) ledger entry: debitRef={}, creditRef={}, amount={}", debitRef, creditRef, amount);
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
