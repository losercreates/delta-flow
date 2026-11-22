package io.deltaflow.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.cluster.domain.ClusterNodeDto;
import io.deltaflow.cluster.domain.LeaderStatusDto;
import io.deltaflow.cluster.service.ClusterLeaderElectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cluster Leader Election & Standby Failover Integration Test")
class ClusterLeaderElectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClusterLeaderElectionService leaderElectionService;

    @BeforeEach
    void setUp() {
        leaderElectionService.stepDown();
        redissonClient.getBucket(ClusterLeaderElectionService.LEADER_KEY).delete();
        redissonClient.getMap(ClusterLeaderElectionService.NODES_KEY).delete();
    }

    @Test
    @DisplayName("Should elect current node as leader when no leader exists")
    void shouldElectCurrentNodeAsLeader() {
        // Trigger election
        leaderElectionService.heartbeatAndElection();

        assertThat(leaderElectionService.isLeader()).isTrue();

        LeaderStatusDto leaderStatus = leaderElectionService.getLeaderStatus();
        assertThat(leaderStatus.isCurrentNodeLeader()).isTrue();
        assertThat(leaderStatus.leaderNodeId()).isEqualTo(leaderElectionService.getNodeId());
        assertThat(leaderStatus.leaseDurationMs()).isEqualTo(5000L);

        List<ClusterNodeDto> nodes = leaderElectionService.getClusterNodes();
        assertThat(nodes).isNotEmpty();
        ClusterNodeDto self = nodes.stream()
                .filter(n -> n.nodeId().equals(leaderElectionService.getNodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(self.role()).isEqualTo("LEADER");
        assertThat(self.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("Secondary node should enter STANDBY when leader key is already held")
    void secondaryNodeShouldEnterStandbyWhenLeaderKeyHeld() {
        // Simulate an existing external leader holding the Redis key
        String externalLeaderId = "external-leader-node-99";
        RBucket<String> leaderBucket = redissonClient.getBucket(ClusterLeaderElectionService.LEADER_KEY);
        leaderBucket.set(externalLeaderId);

        // Current node attempts election
        leaderElectionService.heartbeatAndElection();

        assertThat(leaderElectionService.isLeader()).isFalse();

        LeaderStatusDto status = leaderElectionService.getLeaderStatus();
        assertThat(status.isCurrentNodeLeader()).isFalse();
        assertThat(status.leaderNodeId()).isEqualTo(externalLeaderId);
    }

    @Test
    @DisplayName("Standby node should acquire leadership upon failover when previous leader key expires")
    void standbyShouldAcquireLeadershipUponFailover() {
        // Given an external leader initially holds the lease
        RBucket<String> leaderBucket = redissonClient.getBucket(ClusterLeaderElectionService.LEADER_KEY);
        leaderBucket.set("crashed-leader-node-42");

        leaderElectionService.heartbeatAndElection();
        assertThat(leaderElectionService.isLeader()).isFalse();

        // When the crashed leader key expires or is deleted (failover simulation)
        leaderBucket.delete();

        // Standby node executes election sweep
        leaderElectionService.heartbeatAndElection();

        // Then standby node assumes leadership
        assertThat(leaderElectionService.isLeader()).isTrue();
        assertThat(leaderElectionService.getLeaderStatus().leaderNodeId()).isEqualTo(leaderElectionService.getNodeId());
    }

    @Test
    @DisplayName("Voluntary step-down should release leader lease immediately")
    void voluntaryStepDownShouldReleaseLease() {
        leaderElectionService.heartbeatAndElection();
        assertThat(leaderElectionService.isLeader()).isTrue();

        leaderElectionService.stepDown();

        assertThat(leaderElectionService.isLeader()).isFalse();
        RBucket<String> leaderBucket = redissonClient.getBucket(ClusterLeaderElectionService.LEADER_KEY);
        assertThat(leaderBucket.isExists()).isFalse();
    }
}
