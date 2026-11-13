package io.deltaflow.outbox.api;

import io.deltaflow.outbox.api.dto.OutboxEventDto;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/outbox")
@Tag(name = "Outbox", description = "Transactional Outbox inspection and monitoring endpoints")
public class OutboxInspectorController {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxInspectorController(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @GetMapping
    @Operation(summary = "List Outbox Events", description = "Retrieves a paginated or filtered list of outbox events.")
    public ResponseEntity<List<OutboxEventDto>> listOutboxEvents(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<OutboxEvent> events;

        if (status != null && !status.isBlank()) {
            events = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                    status.toUpperCase(),
                    PageRequest.of(0, boundedLimit)
            );
        } else {
            events = outboxEventRepository.findAll(
                    PageRequest.of(0, boundedLimit, Sort.by(Sort.Direction.ASC, "createdAt"))
            ).getContent();
        }

        List<OutboxEventDto> dtoList = events.stream()
                .map(OutboxEventDto::from)
                .toList();

        return ResponseEntity.ok(dtoList);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get Outbox Event Details", description = "Retrieves the exact state, payload, and audit info for an outbox event.")
    public ResponseEntity<OutboxEventDto> getOutboxEvent(@PathVariable UUID eventId) {
        return outboxEventRepository.findById(eventId)
                .map(OutboxEventDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

