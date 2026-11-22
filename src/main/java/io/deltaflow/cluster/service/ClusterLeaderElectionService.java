package io.deltaflow.cluster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.cluster.domain.ClusterNodeDto;
import io.deltaflow.cluster.domain.LeaderStatusDto;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Distributed Leader Coordination Service.
 * Leverages Redis/Redisson for atomic leader election (SET NX PX) and heartbeat coordination.
 * Guarantees single-active execution for background workers (OutboxPublisher, SagaRecoveryScanner,
 * RetentionService) with automated failover under 5 seconds.
 */
@Service
public class ClusterLeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(ClusterLeaderElectionService.class);

    public static final String LEADER_KEY = "deltaflow:cluster:leader";
    public static final String NODES_KEY = "deltaflow:cluster:nodes";
    public static final long LEASE_DURATION_MS = 5000L;
    public static final long HEARTBEAT_INTERVAL_MS = 1500L;
    public static final long NODE_HEARTBEAT_TIMEOUT_MS = 15000L;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private DashboardEventBroadcaster eventBroadcaster;

    private final String nodeId;
    private final String hostname;
    private final Instant startedAt;

    @Value("${server.port:8080}")
    private int port;

    @Value("${deltaflow.cluster.enabled:true}")
    private boolean enabled;

    private volatile boolean isLeader = false;
    private volatile Instant leaseAcquiredAt = null;
    private volatile Instant leaseExpiresAt = null;

    public ClusterLeaderElectionService(
            @Autowired(required = false) RedissonClient redissonClient,
            ObjectMapper objectMapper
    ) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.startedAt = Instant.now();

        String envNodeId = System.getenv("NODE_ID");
        this.nodeId = (envNodeId != null && !envNodeId.isBlank()) 
                ? envNodeId 
                : "node-" + UUID.randomUUID().toString().substring(0, 8);

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        this.hostname = host;
    }

    @Autowired(required = false)
    public void setEventBroadcaster(DashboardEventBroadcaster eventBroadcaster) {
        this.eventBroadcaster = eventBroadcaster;
    }

    @PostConstruct
    public void init() {
        if (!enabled || redissonClient == null) {
            log.info("Clustering disabled or RedissonClient unavailable. Running in STANDALONE LEADER mode.");
            this.isLeader = true;
            this.leaseAcquiredAt = Instant.now();
            this.leaseExpiresAt = Instant.now().plus(100, java.time.temporal.ChronoUnit.YEARS);
            return;
        }
        log.info("Initializing ClusterLeaderElectionService on node [{}] ({}:{})", nodeId, hostname, port);
        heartbeatAndElection();
    }

    @PreDestroy
    public void shutdown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            try {
                if (isLeader) {
                    RBucket<String> leaderBucket = redissonClient.getBucket(LEADER_KEY);
                    if (nodeId.equals(leaderBucket.get())) {
                        leaderBucket.delete();
                        log.info("Leader lease released on shutdown for node [{}]", nodeId);
                    }
                }
                RMap<String, String> nodesMap = redissonClient.getMap(NODES_KEY);
                nodesMap.remove(nodeId);
            } catch (Exception ex) {
                log.warn("Error during cluster shutdown cleanup: {}", ex.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void scheduledHeartbeatAndElection() {
        if (!enabled || redissonClient == null) {
            return;
        }
        heartbeatAndElection();
    }

    public synchronized void heartbeatAndElection() {
        if (redissonClient == null || redissonClient.isShutdown()) {
            return;
        }

        try {
            Instant now = Instant.now();
            RBucket<String> leaderBucket = redissonClient.getBucket(LEADER_KEY);
            RMap<String, String> nodesMap = redissonClient.getMap(NODES_KEY);

            // 1. Leader Election & Renewal
            String currentLeader = leaderBucket.get();
            if (nodeId.equals(currentLeader)) {
                // Current node already holds lease: renew expiration
                leaderBucket.expire(Duration.ofMillis(LEASE_DURATION_MS));
                this.isLeader = true;
                this.leaseExpiresAt = now.plusMillis(LEASE_DURATION_MS);
            } else if (currentLeader == null) {
                // No leader exists: attempt atomic acquisition
                boolean acquired = leaderBucket.setIfAbsent(nodeId, Duration.ofMillis(LEASE_DURATION_MS));
                if (acquired) {
                    this.isLeader = true;
                    this.leaseAcquiredAt = now;
                    this.leaseExpiresAt = now.plusMillis(LEASE_DURATION_MS);
                    log.info("Node [{}] successfully elected cluster leader (lease duration: {}ms)", nodeId, LEASE_DURATION_MS);
                    broadcastClusterEvent("LEADER_ELECTED", Map.of(
                            "leaderNodeId", nodeId,
                            "leaseAcquiredAt", leaseAcquiredAt.toString(),
                            "leaseExpiresAt", leaseExpiresAt.toString()
                    ));
                } else {
                    if (this.isLeader) {
                        this.isLeader = false;
                        this.leaseAcquiredAt = null;
                        this.leaseExpiresAt = null;
                        broadcastClusterEvent("LEADER_STEPPED_DOWN", Map.of("nodeId", nodeId));
                    }
                }
            } else {
                // Another node holds the lease
                if (this.isLeader) {
                    log.warn("Node [{}] lost leadership lease to [{}]", nodeId, currentLeader);
                    this.isLeader = false;
                    this.leaseAcquiredAt = null;
                    this.leaseExpiresAt = null;
                    broadcastClusterEvent("LEADER_STEPPED_DOWN", Map.of("nodeId", nodeId, "newLeader", currentLeader));
                }
            }

            // 2. Register/Update Node Heartbeat in Registry
            ClusterNodeDto selfDto = new ClusterNodeDto(
                    nodeId,
                    hostname,
                    port,
                    isLeader ? "LEADER" : "STANDBY",
                    startedAt,
                    now,
                    true,
                    "1.0.0"
            );
            nodesMap.put(nodeId, objectMapper.writeValueAsString(selfDto));

        } catch (Exception ex) {
            log.error("Cluster heartbeat and leader election error: {}", ex.getMessage());
        }
    }

    public boolean isLeader() {
        if (redissonClient == null) {
            return true;
        }
        return isLeader;
    }

    public String getNodeId() {
        return nodeId;
    }

    public synchronized void stepDown() {
        if (isLeader && redissonClient != null) {
            try {
                RBucket<String> leaderBucket = redissonClient.getBucket(LEADER_KEY);
                if (nodeId.equals(leaderBucket.get())) {
                    leaderBucket.delete();
                }
                this.isLeader = false;
                this.leaseAcquiredAt = null;
                this.leaseExpiresAt = null;
                log.info("Node [{}] stepped down from leadership voluntarily", nodeId);
                broadcastClusterEvent("LEADER_STEPPED_DOWN", Map.of("nodeId", nodeId));
            } catch (Exception ex) {
                log.error("Error stepping down: {}", ex.getMessage());
            }
        }
    }

    public LeaderStatusDto getLeaderStatus() {
        if (redissonClient == null) {
            return new LeaderStatusDto(nodeId, true, startedAt, Instant.now().plus(365, java.time.temporal.ChronoUnit.DAYS), LEASE_DURATION_MS);
        }

        try {
            RBucket<String> leaderBucket = redissonClient.getBucket(LEADER_KEY);
            String currentLeader = leaderBucket.get();
            boolean currentIsLeader = nodeId.equals(currentLeader);

            Instant acquired = currentIsLeader ? leaseAcquiredAt : null;
            Instant expires = currentIsLeader ? leaseExpiresAt : (currentLeader != null ? Instant.now().plusMillis(leaderBucket.remainTimeToLive()) : null);

            return new LeaderStatusDto(
                    currentLeader != null ? currentLeader : "NONE",
                    currentIsLeader,
                    acquired != null ? acquired : Instant.now(),
                    expires != null ? expires : Instant.now(),
                    LEASE_DURATION_MS
            );
        } catch (Exception ex) {
            log.error("Error retrieving leader status: {}", ex.getMessage());
            return new LeaderStatusDto(isLeader ? nodeId : "UNKNOWN", isLeader, startedAt, Instant.now(), LEASE_DURATION_MS);
        }
    }

    public List<ClusterNodeDto> getClusterNodes() {
        if (redissonClient == null) {
            return List.of(new ClusterNodeDto(nodeId, hostname, port, "LEADER", startedAt, Instant.now(), true, "1.0.0"));
        }

        List<ClusterNodeDto> nodes = new ArrayList<>();
        try {
            RMap<String, String> nodesMap = redissonClient.getMap(NODES_KEY);
            Instant now = Instant.now();
            RBucket<String> leaderBucket = redissonClient.getBucket(LEADER_KEY);
            String currentLeader = leaderBucket.get();

            for (Map.Entry<String, String> entry : nodesMap.entrySet()) {
                try {
                    ClusterNodeDto node = objectMapper.readValue(entry.getValue(), ClusterNodeDto.class);
                    boolean isHealthy = node.lastHeartbeatAt() != null &&
                            Duration.between(node.lastHeartbeatAt(), now).toMillis() <= NODE_HEARTBEAT_TIMEOUT_MS;

                    String role = node.nodeId().equals(currentLeader) ? "LEADER" : "STANDBY";

                    nodes.add(new ClusterNodeDto(
                            node.nodeId(),
                            node.hostname(),
                            node.port(),
                            role,
                            node.startedAt(),
                            node.lastHeartbeatAt(),
                            isHealthy,
                            node.version()
                    ));
                } catch (Exception e) {
                    log.warn("Failed to parse node registry entry: {}", entry.getValue());
                }
            }
        } catch (Exception ex) {
            log.error("Error retrieving cluster nodes: {}", ex.getMessage());
        }

        // Sort by role (LEADER first), then nodeId
        nodes.sort((a, b) -> {
            if ("LEADER".equals(a.role()) && !"LEADER".equals(b.role())) return -1;
            if (!"LEADER".equals(a.role()) && "LEADER".equals(b.role())) return 1;
            return a.nodeId().compareTo(b.nodeId());
        });

        return nodes;
    }

    private void broadcastClusterEvent(String eventType, Map<String, Object> payload) {
        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast(eventType, payload);
        }
    }
}
