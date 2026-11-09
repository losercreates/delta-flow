package io.deltaflow.idempotency.api;

import io.deltaflow.common.exception.IdempotencyConflictException;
import io.deltaflow.common.exception.PayloadMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class IdempotencyExceptionHandler {

    @ExceptionHandler(PayloadMismatchException.class)
    public ResponseEntity<Map<String, Object>> handlePayloadMismatch(PayloadMismatchException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("idempotencyKey", ex.getIdempotencyKey());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("idempotencyKey", ex.getIdempotencyKey());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}

