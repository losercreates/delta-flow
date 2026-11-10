package io.deltaflow.saga.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransferRequestDto(
        @NotBlank(message = "sourceAccountId must not be blank")
        String sourceAccountId,

        @NotBlank(message = "targetAccountId must not be blank")
        String targetAccountId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        Double amount,

        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
        String currency
) {}

