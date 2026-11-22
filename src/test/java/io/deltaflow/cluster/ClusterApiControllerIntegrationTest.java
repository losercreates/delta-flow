package io.deltaflow.cluster;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.cluster.domain.RetentionTriggerRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cluster API Controller Integration Test")
class ClusterApiControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/v1/cluster/nodes should return active nodes")
    void shouldReturnClusterNodes() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/cluster/nodes", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nodeId", "role");
    }

    @Test
    @DisplayName("GET /api/v1/cluster/leader should return leader status")
    void shouldReturnLeaderStatus() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/cluster/leader", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("leaderNodeId", "isCurrentNodeLeader");
    }

    @Test
    @DisplayName("GET /api/v1/cluster/retention/status should return retention metrics")
    void shouldReturnRetentionStatus() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/cluster/retention/status", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("enabled", "retentionDays", "batchSize");
    }

    @Test
    @DisplayName("POST /api/v1/cluster/retention/trigger should execute on-demand pruning")
    void shouldTriggerRetentionPruning() {
        RetentionTriggerRequest request = new RetentionTriggerRequest(30, 100, "operator-api-test", "API test trigger");
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/cluster/retention/trigger", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("totalPrunedRecords");
    }

    @Test
    @DisplayName("POST /api/v1/cluster/outbox/reclaim-leases should trigger lease reclamation")
    void shouldTriggerOutboxLeaseReclamation() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/cluster/outbox/reclaim-leases", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("scannedCount", "reclaimedCount", "deadLetteredCount");
    }
}
