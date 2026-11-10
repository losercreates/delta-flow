package io.deltaflow.saga.api;

import io.deltaflow.saga.api.dto.SagaInstanceDto;
import io.deltaflow.saga.api.dto.SagaStepDto;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.service.SagaJournalStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sagas")
@Tag(name = "Sagas", description = "Write-Ahead Log (WAL) journal inspection and status retrieval")
public class SagaInspectorController {

    private final SagaJournalStore journalStore;

    public SagaInspectorController(SagaJournalStore journalStore) {
        this.journalStore = journalStore;
    }

    @GetMapping("/{sagaId}")
    @Operation(summary = "Get Saga Instance Status", description = "Retrieves the current lifecycle status and details of a saga instance.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Saga instance details"),
            @ApiResponse(responseCode = "404", description = "Saga not found")
    })
    public ResponseEntity<SagaInstanceDto> getSaga(
            @Parameter(description = "UUID of the saga instance", required = true)
            @PathVariable UUID sagaId
    ) {
        SagaInstance instance = journalStore.findSaga(sagaId)
                .orElseThrow(() -> new NoSuchElementException("Saga instance not found: " + sagaId));

        SagaInstanceDto dto = new SagaInstanceDto(
                instance.getSagaId(),
                instance.getTenantId(),
                instance.getIdempotencyKey(),
                instance.getSagaType(),
                instance.getStatus(),
                instance.getCurrentStep(),
                instance.getPayload(),
                instance.getResultPayload(),
                instance.getCreatedAt(),
                instance.getUpdatedAt()
        );

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{sagaId}/steps")
    @Operation(summary = "Get Saga Step Journals (WAL)", description = "Returns the ordered list of Write-Ahead Log journal records for all steps in the saga.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ordered list of step execution journals"),
            @ApiResponse(responseCode = "404", description = "Saga not found")
    })
    public ResponseEntity<List<SagaStepDto>> getSagaSteps(
            @Parameter(description = "UUID of the saga instance", required = true)
            @PathVariable UUID sagaId
    ) {
        journalStore.findSaga(sagaId)
                .orElseThrow(() -> new NoSuchElementException("Saga instance not found: " + sagaId));

        List<SagaStep> steps = journalStore.getStepsForSaga(sagaId);
        List<SagaStepDto> dtos = steps.stream()
                .map(s -> new SagaStepDto(
                        s.getStepId(),
                        s.getSagaId(),
                        s.getStepName(),
                        s.getStepIndex(),
                        s.getStatus(),
                        s.getRequestPayload(),
                        s.getResponsePayload(),
                        s.getErrorDetails(),
                        s.getCreatedAt(),
                        s.getUpdatedAt()
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }
}
