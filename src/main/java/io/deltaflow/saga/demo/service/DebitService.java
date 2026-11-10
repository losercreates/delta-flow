package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DebitService {

    private static final Logger log = LoggerFactory.getLogger(DebitService.class);

    public record DebitResult(String debitId, String accountId, double amount, String status) {}

    public DebitResult debit(String accountId, double amount, String idempotencyKey) {
        log.info("Executing debit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
        if (amount > 1_000_000.0) {
            throw new IllegalArgumentException("Amount exceeds maximum debit limit");
        }
        String debitId = "deb_" + UUID.randomUUID().toString().substring(0, 8);
        return new DebitResult(debitId, accountId, amount, "SUCCESS");
    }

    public void compensateDebit(String accountId, double amount, String idempotencyKey) {
        log.info("Compensating debit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
    }
}

