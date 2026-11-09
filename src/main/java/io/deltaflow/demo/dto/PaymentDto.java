package io.deltaflow.demo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PaymentDto {

    public record Request(
        @NotBlank(message = "sourceAccountId must not be blank")
        String sourceAccountId,

        @NotBlank(message = "targetAccountId must not be blank")
        String targetAccountId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be 3 characters")
        String currency
    ) {}

    public record Response(
        UUID transactionId,
        String status,
        BigDecimal amount,
        String currency,
        Instant timestamp
    ) {}
}

