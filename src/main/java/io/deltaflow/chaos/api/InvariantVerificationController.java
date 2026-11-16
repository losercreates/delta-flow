package io.deltaflow.chaos.api;

import io.deltaflow.chaos.domain.InvariantVerificationReport;
import io.deltaflow.chaos.service.FinancialInvariantVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invariants")
@CrossOrigin(origins = "*")
public class InvariantVerificationController {

    private final FinancialInvariantVerifier verifier;

    @Autowired
    public InvariantVerificationController(FinancialInvariantVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/verify")
    public ResponseEntity<InvariantVerificationReport> verifyFinancialInvariants() {
        InvariantVerificationReport report = verifier.verifyInvariants();
        return ResponseEntity.ok(report);
    }
}
