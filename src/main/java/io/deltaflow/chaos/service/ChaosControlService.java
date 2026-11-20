package io.deltaflow.chaos.service;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.deltaflow.chaos.domain.*;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.dashboard.dto.CircuitBreakerStatusDto;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChaosControlService {

    private static final Logger log = LoggerFactory.getLogger(ChaosControlService.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final DashboardEventBroadcaster eventBroadcaster;
    private final AuditLogger auditLogger;

    private final String toxiproxyHost;
    private final int toxiproxyPort;
    private volatile boolean toxiproxyEnabled;

    private ToxiproxyClient toxiproxyClient;
    private final Map<String, List<ToxicDetailDto>> simulatedToxics = new ConcurrentHashMap<>();

    @Autowired
    public ChaosControlService(
            @Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry,
            DashboardEventBroadcaster eventBroadcaster,
            AuditLogger auditLogger,
            @Value("${deltaflow.chaos.toxiproxy.host:localhost}") String toxiproxyHost,
            @Value("${deltaflow.chaos.toxiproxy.port:8474}") int toxiproxyPort,
            @Value("${deltaflow.chaos.toxiproxy.enabled:false}") boolean toxiproxyEnabled
    ) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventBroadcaster = eventBroadcaster;
        this.auditLogger = auditLogger;
        this.toxiproxyHost = toxiproxyHost;
        this.toxiproxyPort = toxiproxyPort;
        this.toxiproxyEnabled = toxiproxyEnabled;

        if (toxiproxyEnabled) {
            try {
                this.toxiproxyClient = new ToxiproxyClient(toxiproxyHost, toxiproxyPort);
            } catch (Exception e) {
                log.warn("Toxiproxy client initialization failed: {}. Operating in simulation fallback mode.", e.getMessage());
                this.toxiproxyClient = null;
            }
        }
    }

    public synchronized void setToxiproxyClient(ToxiproxyClient client) {
        this.toxiproxyClient = client;
        this.toxiproxyEnabled = (client != null);
    }

    public ChaosStatusDto getChaosStatus() {
        List<ProxyStatusDto> proxyList = new ArrayList<>();
        int totalActiveToxics = 0;
        boolean isConnected = false;

        if (toxiproxyEnabled && toxiproxyClient != null) {
            try {
                List<Proxy> proxies = toxiproxyClient.getProxies();
                isConnected = true;
                for (Proxy p : proxies) {
                    List<ToxicDetailDto> toxics = new ArrayList<>();
                    p.toxics().getAll().forEach(t -> {
                        toxics.add(new ToxicDetailDto(
                                t.getName(),
                                t.getClass().getSimpleName(),
                                t.getStream().name(),
                                t.getToxicity(),
                                Collections.emptyMap()
                        ));
                    });
                    totalActiveToxics += toxics.size();
                    proxyList.add(new ProxyStatusDto(
                            p.getName(),
                            p.getListen(),
                            p.getUpstream(),
                            p.isEnabled(),
                            toxics
                    ));
                }
            } catch (Exception e) {
                log.debug("Could not query live Toxiproxy (fallback to simulation): {}", e.getMessage());
            }
        }

        if (proxyList.isEmpty()) {
            // Provide simulated default proxies (e.g. postgres-proxy, redis-proxy, payment-gateway)
            List<String> defaultProxies = List.of("postgres-proxy", "redis-proxy", "payment-gateway");
            for (String pName : defaultProxies) {
                List<ToxicDetailDto> toxics = simulatedToxics.getOrDefault(pName, Collections.emptyList());
                totalActiveToxics += toxics.size();
                proxyList.add(new ProxyStatusDto(
                        pName,
                        "127.0.0.1:" + (pName.hashCode() % 1000 + 8000),
                        "upstream:0",
                        true,
                        new ArrayList<>(toxics)
                ));
            }
        }

        List<CircuitBreakerStatusDto> cbList = new ArrayList<>();
        if (circuitBreakerRegistry != null) {
            for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
                cbList.add(new CircuitBreakerStatusDto(
                        cb.getName(),
                        cb.getState().name(),
                        cb.getMetrics().getFailureRate(),
                        cb.getMetrics().getNumberOfBufferedCalls()
                ));
            }
        }

        return new ChaosStatusDto(
                isConnected,
                totalActiveToxics,
                proxyList,
                cbList
        );
    }

    public synchronized ProxyStatusDto injectChaos(ChaosInjectionRequest request) {
        log.warn("Operator [{}] injecting chaos [{}] on proxy [{}]: justification='{}'",
                request.operatorId(), request.toxicType(), request.proxyName(), request.justification());

        List<ToxicDetailDto> appliedToxics = new ArrayList<>();

        if (toxiproxyEnabled && toxiproxyClient != null) {
            try {
                Proxy proxy = toxiproxyClient.getProxyOrNull(request.proxyName());
                if (proxy != null) {
                    applyToxicToProxy(proxy, request);
                    proxy.toxics().getAll().forEach(t -> appliedToxics.add(new ToxicDetailDto(
                            t.getName(), t.getClass().getSimpleName(), t.getStream().name(), t.getToxicity(), Collections.emptyMap()
                    )));
                }
            } catch (Exception e) {
                log.error("Failed to inject toxic via Toxiproxy: {}", e.getMessage(), e);
            }
        }

        if (appliedToxics.isEmpty()) {
            // Simulated toxic
            String type = request.toxicType().toUpperCase();
            Map<String, Object> attrs = new LinkedHashMap<>();
            if ("LATENCY".equals(type)) {
                attrs.put("latency", request.latencyMs() != null ? request.latencyMs() : 500L);
                attrs.put("jitter", request.jitterMs() != null ? request.jitterMs() : 50L);
            } else if ("PACKET_LOSS".equals(type)) {
                attrs.put("dropRate", request.dropRate() != null ? request.dropRate() : 0.30f);
            }
            ToxicDetailDto simulated = new ToxicDetailDto(
                    "simulated-" + type.toLowerCase(),
                    type,
                    "DOWNSTREAM",
                    request.dropRate() != null ? request.dropRate() : 1.0f,
                    attrs
            );
            simulatedToxics.computeIfAbsent(request.proxyName(), k -> new ArrayList<>()).add(simulated);
            appliedToxics.add(simulated);
        }

        // Immutable Audit Log
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("proxyName", request.proxyName());
        auditDetails.put("toxicType", request.toxicType());
        auditDetails.put("operatorId", request.operatorId());
        auditDetails.put("justification", request.justification());
        auditDetails.put("appliedToxics", appliedToxics);
        auditLogger.logEvent("CHAOS_INJECTED", "SYSTEM", "CHAOS-" + request.proxyName(), auditDetails);

        // SSE Broadcast
        eventBroadcaster.broadcast("CHAOS_INJECTED", auditDetails);

        return new ProxyStatusDto(
                request.proxyName(),
                "127.0.0.1",
                "upstream",
                true,
                appliedToxics
        );
    }

    private void applyToxicToProxy(Proxy proxy, ChaosInjectionRequest request) throws IOException {
        String type = request.toxicType().toUpperCase();
        if ("LATENCY".equals(type)) {
            long latency = request.latencyMs() != null ? request.latencyMs() : 500L;
            long jitter = request.jitterMs() != null ? request.jitterMs() : 50L;
            proxy.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, latency).setJitter(jitter);
        } else if ("PACKET_LOSS".equals(type) || "SLICER".equals(type)) {
            float rate = request.dropRate() != null ? request.dropRate() : 0.30f;
            proxy.toxics().slicer("packet-slice-toxic", ToxicDirection.DOWNSTREAM, 100, 50).setToxicity(rate);
        } else if ("PARTITION".equals(type)) {
            proxy.disable();
        }
    }

    public synchronized void healChaos(String proxyName, String operatorId, String justification) {
        log.info("Operator [{}] healing chaos on proxy [{}] (justification='{}')",
                operatorId, proxyName != null ? proxyName : "ALL", justification);

        if (toxiproxyEnabled && toxiproxyClient != null) {
            try {
                if (proxyName != null && !proxyName.isBlank()) {
                    Proxy p = toxiproxyClient.getProxyOrNull(proxyName);
                    if (p != null) {
                        p.enable();
                        p.toxics().getAll().forEach(t -> {
                            try { t.remove(); } catch (Exception ignored) {}
                        });
                    }
                } else {
                    for (Proxy p : toxiproxyClient.getProxies()) {
                        p.enable();
                        p.toxics().getAll().forEach(t -> {
                            try { t.remove(); } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception e) {
                log.warn("Error resetting toxics via Toxiproxy client: {}", e.getMessage());
            }
        }

        if (proxyName != null && !proxyName.isBlank()) {
            simulatedToxics.remove(proxyName);
        } else {
            simulatedToxics.clear();
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("proxyName", proxyName != null ? proxyName : "ALL");
        auditDetails.put("operatorId", operatorId != null ? operatorId : "OPERATOR");
        auditDetails.put("justification", justification != null ? justification : "Heal all faults");
        auditLogger.logEvent("CHAOS_HEALED", "SYSTEM", "CHAOS-HEAL", auditDetails);

        eventBroadcaster.broadcast("CHAOS_HEALED", auditDetails);
    }

    public CircuitBreakerStatusDto transitionCircuitBreaker(String name, CircuitBreakerTransitionRequest request) {
        log.warn("Operator [{}] manually transitioning circuit breaker [{}] to state [{}] (justification='{}')",
                request.operatorId(), name, request.targetState(), request.justification());

        if (circuitBreakerRegistry == null) {
            throw new IllegalStateException("CircuitBreakerRegistry is not configured");
        }

        CircuitBreaker cb = circuitBreakerRegistry.find(name)
                .orElseThrow(() -> new IllegalArgumentException("Circuit breaker not found: " + name));

        String target = request.targetState().toUpperCase();
        switch (target) {
            case "OPEN" -> cb.transitionToOpenState();
            case "CLOSED" -> cb.transitionToClosedState();
            case "HALF_OPEN" -> cb.transitionToHalfOpenState();
            default -> throw new IllegalArgumentException("Invalid circuit breaker target state: " + target);
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("circuitBreakerName", name);
        auditDetails.put("targetState", target);
        auditDetails.put("operatorId", request.operatorId());
        auditDetails.put("justification", request.justification());
        auditLogger.logEvent("CIRCUIT_BREAKER_TRANSITIONED", "SYSTEM", "CB-" + name, auditDetails);

        eventBroadcaster.broadcast("CIRCUIT_BREAKER_TRANSITIONED", auditDetails);

        return new CircuitBreakerStatusDto(
                cb.getName(),
                cb.getState().name(),
                cb.getMetrics().getFailureRate(),
                cb.getMetrics().getNumberOfBufferedCalls()
        );
    }
}
