package io.deltaflow.saga.api;

import io.deltaflow.saga.api.dto.TransferCompensationResponseDto;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.exception.SagaCompensatedException;
import io.deltaflow.saga.service.SagaCoordinator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Declarative saga orchestration for distributed financial transfers")
public class TransferController {

    private final SagaCoordinator sagaCoordinator;
    private final SagaDefinition<TransferRequestDto, TransferResponseDto> transferSagaDefinition;

    public TransferController(
            SagaCoordinator sagaCoordinator,
            SagaDefinition<TransferRequestDto, TransferResponseDto> transferSagaDefinition
    ) {
        this.sagaCoordinator = sagaCoordinator;
        this.transferSagaDefinition = transferSagaDefinition;
    }

    @PostMapping
    @Operation(summary = "Initiate an Account-to-Account Transfer Saga", description = "Executes a 3-step sequential saga (Debit -> Credit -> Ledger) guarded by an Idempotency-Key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transfer executed or replayed from cache"),
            @ApiResponse(responseCode = "400", description = "Missing or malformed request parameters"),
            @ApiResponse(responseCode = "409", description = "Payload tampering detected or concurrent conflict"),
            @ApiResponse(responseCode = "422", description = "Transfer aborted and rolled back via backward compensation"),
            @ApiResponse(responseCode = "500", description = "Unrecoverable step failure or downstream outage")
    })
    public ResponseEntity<?> initiateTransfer(
            @Parameter(description = "Unique client idempotency key", required = true)
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Parameter(description = "Multi-tenant partition ID", required = false)
            @RequestHeader(value = "X-Tenant-ID", required = false, defaultValue = "default") String tenantId,
            @Valid @RequestBody TransferRequestDto requestDto
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        UUID sagaId = UUID.randomUUID();
        try {
            TransferResponseDto responseDto = sagaCoordinator.executeSaga(
                    transferSagaDefinition,
                    sagaId,
                    tenantId,
                    idempotencyKey,
                    requestDto
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
        } catch (SagaCompensatedException e) {
            TransferCompensationResponseDto compensationDto = new TransferCompensationResponseDto(
                    e.getSagaId(),
                    e.getIdempotencyKey(),
                    "COMPENSATED",
                    e.getFailedStep(),
                    e.getMessage(),
                    e.getCompensatedSteps(),
                    Instant.now()
            );
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(compensationDto);
        }
    }
}
