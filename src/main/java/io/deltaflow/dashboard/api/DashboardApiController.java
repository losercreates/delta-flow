package io.deltaflow.dashboard.api;

import io.deltaflow.dashboard.dto.*;
import io.deltaflow.dashboard.service.DashboardService;
import io.deltaflow.dashboard.service.OperatorInterventionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
@CrossOrigin(origins = "*")
public class DashboardApiController {

    private final DashboardService dashboardService;
    private final OperatorInterventionService interventionService;

    @Autowired
    public DashboardApiController(
            DashboardService dashboardService,
            @Autowired(required = false) OperatorInterventionService interventionService
    ) {
        this.dashboardService = dashboardService;
        this.interventionService = interventionService;
    }

    @GetMapping("/sagas")
    public ResponseEntity<List<SagaSummaryDto>> listSagas(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(dashboardService.listSagas(status, tenantId, page, size));
    }

    @GetMapping("/sagas/{sagaId}/graph")
    public ResponseEntity<SagaGraphDto> getSagaGraph(@PathVariable UUID sagaId) {
        return dashboardService.getSagaGraph(sagaId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sagas/{sagaId}/intervene")
    public ResponseEntity<OperatorInterventionResult> intervene(
            @PathVariable UUID sagaId,
            @Valid @RequestBody OperatorInterventionRequest request
    ) {
        if (interventionService == null) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
        return ResponseEntity.ok(interventionService.intervene(sagaId, request));
    }

    @GetMapping("/idempotency")
    public ResponseEntity<List<IdempotencyForensicDto>> searchIdempotency(
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String payloadHash,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(dashboardService.searchIdempotencyRecords(key, payloadHash, tenantId, status));
    }

    @GetMapping("/telemetry")
    public ResponseEntity<TelemetrySummaryDto> getTelemetry() {
        return ResponseEntity.ok(dashboardService.getTelemetry());
    }
}
