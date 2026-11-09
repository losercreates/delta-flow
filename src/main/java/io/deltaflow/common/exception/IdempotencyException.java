package io.deltaflow.common.exception;

public abstract class IdempotencyException extends RuntimeException {

    private final String errorCode;

    public IdempotencyException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public IdempotencyException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

