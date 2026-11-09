package io.deltaflow.common.exception;

public class PayloadMismatchException extends IdempotencyConflictException {

    private final String existingHash;
    private final String incomingHash;

    public PayloadMismatchException(String idempotencyKey, String existingHash, String incomingHash) {
        super(
            "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
            "Payload SHA-256 digest does not match the original request for this idempotency key.",
            idempotencyKey
        );
        this.existingHash = existingHash;
        this.incomingHash = incomingHash;
    }

    public String getExistingHash() {
        return existingHash;
    }

    public String getIncomingHash() {
        return incomingHash;
    }
}

