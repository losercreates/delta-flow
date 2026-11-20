package io.deltaflow.chaos.api;

import io.deltaflow.chaos.domain.*;
import io.deltaflow.chaos.service.ChaosControlService;
import io.deltaflow.dashboard.dto.CircuitBreakerStatusDto;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/chaos")
@CrossOrigin(origins = "*")
public class ChaosControlController {

    private final ChaosControlService chaosControlService;

    @Autowired
    public ChaosControlController(ChaosControlService chaosControlService) {
        this.chaosControlService = chaosControlService;
    }

    @GetMapping("/status")
    public ResponseEntity<ChaosStatusDto> getChaosStatus() {
        return ResponseEntity.ok(chaosControlService.getChaosStatus());
    }

    @PostMapping("/inject")
    public ResponseEntity<ProxyStatusDto> injectChaos(@Valid @RequestBody ChaosInjectionRequest request) {
        ProxyStatusDto result = chaosControlService.injectChaos(request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/heal")
    public ResponseEntity<Map<String, String>> healChaos(@RequestBody(required = false) Map<String, String> body) {
        String proxyName = (body != null) ? body.get("proxyName") : null;
        String operatorId = (body != null) ? body.get("operatorId") : "OPERATOR";
        String justification = (body != null) ? body.get("justification") : "Heal all faults";

        chaosControlService.healChaos(proxyName, operatorId, justification);
        return ResponseEntity.ok(Map.of("status", "HEALED", "proxy", proxyName != null ? proxyName : "ALL"));
    }

    @PostMapping("/circuit-breakers/{name}/transition")
    public ResponseEntity<CircuitBreakerStatusDto> transitionCircuitBreaker(
            @PathVariable String name,
            @Valid @RequestBody CircuitBreakerTransitionRequest request
    ) {
        CircuitBreakerStatusDto result = chaosControlService.transitionCircuitBreaker(name, request);
        return ResponseEntity.ok(result);
    }
}
