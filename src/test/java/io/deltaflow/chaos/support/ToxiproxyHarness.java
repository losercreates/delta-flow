package io.deltaflow.chaos.support;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ToxiproxyHarness {

    private static final Logger log = LoggerFactory.getLogger(ToxiproxyHarness.class);

    private final ToxiproxyClient client;

    public ToxiproxyHarness(String host, int port) {
        this.client = new ToxiproxyClient(host, port);
    }

    public ToxiproxyHarness(ToxiproxyClient client) {
        this.client = client;
    }

    public Proxy createProxy(String name, String listenAddress, String upstreamAddress) throws IOException {
        Proxy proxy = client.getProxyOrNull(name);
        if (proxy != null) {
            proxy.delete();
        }
        log.info("Creating Toxiproxy [{}] listening on [{}] -> upstream [{}]", name, listenAddress, upstreamAddress);
        return client.createProxy(name, listenAddress, upstreamAddress);
    }

    public Proxy getProxy(String name) throws IOException {
        return client.getProxy(name);
    }

    public void addLatency(String proxyName, long latencyMs, long jitterMs) throws IOException {
        Proxy proxy = client.getProxy(proxyName);
        log.info("Injecting latency toxic on [{}] ({}ms ± {}ms)", proxyName, latencyMs, jitterMs);
        proxy.toxics()
                .latency("latency-toxic", ToxicDirection.DOWNSTREAM, latencyMs)
                .setJitter(jitterMs);
    }

    public void addPacketLoss(String proxyName, float dropRate) throws IOException {
        Proxy proxy = client.getProxy(proxyName);
        log.info("Injecting packet drop / slicer toxic on [{}] (rate: {})", proxyName, dropRate);
        // In Toxiproxy, slicer toxic chops packets into small slices with delay/loss
        proxy.toxics()
                .slicer("packet-slice-toxic", ToxicDirection.DOWNSTREAM, 100, 50)
                .setToxicity(dropRate);
    }

    public void cutConnection(String proxyName) throws IOException {
        Proxy proxy = client.getProxy(proxyName);
        log.warn("Cutting TCP connection on Toxiproxy [{}]", proxyName);
        proxy.disable();
    }

    public void restoreConnection(String proxyName) throws IOException {
        Proxy proxy = client.getProxy(proxyName);
        log.info("Restoring TCP connection on Toxiproxy [{}]", proxyName);
        proxy.enable();
    }

    public void resetAllToxics(String proxyName) throws IOException {
        Proxy proxy = client.getProxyOrNull(proxyName);
        if (proxy != null) {
            proxy.enable();
            proxy.toxics().getAll().forEach(t -> {
                try {
                    t.remove();
                } catch (IOException e) {
                    log.warn("Failed to remove toxic [{}]: {}", t.getName(), e.getMessage());
                }
            });
            log.info("Reset all toxics on proxy [{}]", proxyName);
        }
    }
}
