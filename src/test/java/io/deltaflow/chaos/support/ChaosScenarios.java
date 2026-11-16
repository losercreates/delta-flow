package io.deltaflow.chaos.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class ChaosScenarios {

    private static final Logger log = LoggerFactory.getLogger(ChaosScenarios.class);

    private ChaosScenarios() {
    }

    public static void applyHostileNetwork(ToxiproxyHarness harness, String proxyName) throws IOException {
        log.warn(">>> CHAOS INJECTION: Applying Hostile Network (30% loss, 500ms latency) to [{}]", proxyName);
        harness.resetAllToxics(proxyName);
        harness.addLatency(proxyName, 500, 150);
        harness.addPacketLoss(proxyName, 0.30f);
    }

    public static void applyHighLatencySpike(ToxiproxyHarness harness, String proxyName, long latencyMs) throws IOException {
        log.warn(">>> CHAOS INJECTION: Applying High Latency Spike ({}ms) to [{}]", latencyMs, proxyName);
        harness.resetAllToxics(proxyName);
        harness.addLatency(proxyName, latencyMs, 50);
    }

    public static void applyTransientPartition(ToxiproxyHarness harness, String proxyName, long durationMs) {
        log.warn(">>> CHAOS INJECTION: Cutting connection on [{}] for {}ms", proxyName, durationMs);
        try {
            harness.cutConnection(proxyName);
            Thread.sleep(durationMs);
            harness.restoreConnection(proxyName);
            log.info(">>> CHAOS HEALED: Connection restored on [{}]", proxyName);
        } catch (Exception e) {
            log.error("Failed to execute transient partition scenario on [{}]: {}", proxyName, e.getMessage());
        }
    }

    public static void healNetwork(ToxiproxyHarness harness, String proxyName) throws IOException {
        log.info(">>> CHAOS HEALED: Clearing all toxics on [{}]", proxyName);
        harness.resetAllToxics(proxyName);
    }
}
