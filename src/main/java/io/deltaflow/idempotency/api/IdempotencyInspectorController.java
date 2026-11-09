package io.deltaflow.idempotency.api;

import io.deltaflow.idempotency.api.dto.IdempotencyRecordDto;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/idempotency/records")
public class IdempotencyInspectorController {

    private final IdempotencyRecordRepository repository;

    public IdempotencyInspectorController(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{key}")
    public ResponseEntity<IdempotencyRecordDto> inspectRecord(
        @PathVariable("key") String key,
        @RequestHeader(value = "X-Tenant-ID", required = false, defaultValue = "default") String tenantId
    ) {
        return repository.findById(tenantId, key)
            .map(this::toDto)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private IdempotencyRecordDto toDto(IdempotencyRecord record) {
        return new IdempotencyRecordDto(
            record.getId().getTenantId(),
            record.getId().getIdempotencyKey(),
            record.getRequestHash(),
            record.getStatus(),
            record.getResponseStatusCode(),
            record.getLeaseExpiresAt(),
            record.getCreatedAt(),
            record.getUpdatedAt()
        );
    }
}

