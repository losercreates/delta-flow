package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    public record CreditResult(String creditId, String accountId, double amount, String status) {}

    public CreditResult credit(String accountId, double amount, String idempotencyKey) {
        log.info("Executing credit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
        String creditId = "crd_" + UUID.randomUUID().toString().substring(0, 8);
        return new CreditResult(creditId, accountId, amount, "SUCCESS");
    }

    public void compensateCredit(String accountId, double amount, String idempotencyKey) {
        log.info("Compensating credit on account: {}, amount: {}, key: {}", accountId, amount, idempotencyKey);
    }
}

