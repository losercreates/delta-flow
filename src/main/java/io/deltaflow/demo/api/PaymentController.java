package io.deltaflow.demo.api;

import io.deltaflow.demo.dto.PaymentDto;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final AtomicInteger executionCount = new AtomicInteger(0);

    @PostMapping
    public ResponseEntity<PaymentDto.Response> createPayment(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Tenant-ID", required = false, defaultValue = "default") String tenantId,
        @Valid @RequestBody PaymentDto.Request request
    ) {
        int count = executionCount.incrementAndGet();
        log.info("Executing payment transfer #{}. Tenant: {}, Key: {}, Amount: {} {}", 
            count, tenantId, idempotencyKey, request.amount(), request.currency());

        PaymentDto.Response response = new PaymentDto.Response(
            UUID.randomUUID(),
            "COMPLETED",
            request.amount(),
            request.currency(),
            Instant.now()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public int getExecutionCount() {
        return executionCount.get();
    }
}

