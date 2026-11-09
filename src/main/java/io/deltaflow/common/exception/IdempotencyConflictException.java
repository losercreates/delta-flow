package io.deltaflow.common.exception;

public class IdempotencyConflictException extends IdempotencyException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String errorCode, String message, String idempotencyKey) {
        super(errorCode, message);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}

