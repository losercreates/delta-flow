package io.deltaflow.chaos.api;

import io.deltaflow.chaos.domain.InvariantVerificationReport;
import io.deltaflow.chaos.service.FinancialInvariantVerifier;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invariants")
@CrossOrigin(origins = "*")
public class InvariantVerificationController {

    private final FinancialInvariantVerifier verifier;
    private final DashboardEventBroadcaster eventBroadcaster;

    @Autowired
    public InvariantVerificationController(
            FinancialInvariantVerifier verifier,
            @Autowired(required = false) DashboardEventBroadcaster eventBroadcaster
    ) {
        this.verifier = verifier;
        this.eventBroadcaster = eventBroadcaster;
    }

    @GetMapping("/verify")
    public ResponseEntity<InvariantVerificationReport> verifyFinancialInvariants(
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        InvariantVerificationReport report = verifier.verifyInvariants(tenantId);
        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("INVARIANT_CHECKED", null, tenantId, report);
        }
        return ResponseEntity.ok(report);
    }
}
