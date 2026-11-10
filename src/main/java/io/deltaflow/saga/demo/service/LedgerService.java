package io.deltaflow.saga.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public record LedgerResult(String entryId, String debitRef, String creditRef, double amount, String currency, String status) {}

    public LedgerResult recordEntry(String debitRef, String creditRef, double amount, String currency) {
        log.info("Recording double-entry ledger: debitRef={}, creditRef={}, amount={}, currency={}", debitRef, creditRef, amount, currency);
        String entryId = "led_" + UUID.randomUUID().toString().substring(0, 8);
        return new LedgerResult(entryId, debitRef, creditRef, amount, currency, "RECORDED");
    }
}

