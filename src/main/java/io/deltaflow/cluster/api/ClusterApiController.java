package io.deltaflow.cluster.api;

import io.deltaflow.cluster.domain.*;
import io.deltaflow.cluster.service.ClusterLeaderElectionService;
import io.deltaflow.cluster.service.IdempotencyRetentionService;
import io.deltaflow.outbox.service.OutboxLeaseRecoveryReaper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterApiController {

    private final ClusterLeaderElectionService leaderElectionService;
    private final IdempotencyRetentionService retentionService;
    private final OutboxLeaseRecoveryReaper outboxLeaseRecoveryReaper;

    public ClusterApiController(
            ClusterLeaderElectionService leaderElectionService,
            IdempotencyRetentionService retentionService,
            OutboxLeaseRecoveryReaper outboxLeaseRecoveryReaper
    ) {
        this.leaderElectionService = leaderElectionService;
        this.retentionService = retentionService;
        this.outboxLeaseRecoveryReaper = outboxLeaseRecoveryReaper;
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<ClusterNodeDto>> getClusterNodes() {
        return ResponseEntity.ok(leaderElectionService.getClusterNodes());
    }

    @GetMapping("/leader")
    public ResponseEntity<LeaderStatusDto> getLeaderStatus() {
        return ResponseEntity.ok(leaderElectionService.getLeaderStatus());
    }

    @GetMapping("/retention/status")
    public ResponseEntity<RetentionSummaryDto> getRetentionStatus() {
        return ResponseEntity.ok(retentionService.getRetentionSummary());
    }

    @PostMapping("/retention/trigger")
    public ResponseEntity<RetentionSummaryDto> triggerRetention(
            @Valid @RequestBody RetentionTriggerRequest request
    ) {
        RetentionSummaryDto summary = retentionService.pruneExpiredRecords(
                request.retentionDays(),
                request.batchSize(),
                request.operatorId(),
                request.justification()
        );
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/outbox/reclaim-leases")
    public ResponseEntity<OutboxLeaseReclaimResult> triggerOutboxLeaseReclamation() {
        return ResponseEntity.ok(outboxLeaseRecoveryReaper.runReaperScan());
    }
}
