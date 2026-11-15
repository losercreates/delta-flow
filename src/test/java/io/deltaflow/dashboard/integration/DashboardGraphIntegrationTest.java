package io.deltaflow.dashboard.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.dashboard.dto.SagaGraphDto;
import io.deltaflow.dashboard.dto.SagaSummaryDto;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DashboardGraphIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaJournalStore journalStore;

    @Test
    @DisplayName("US1: GET /api/v1/dashboard/sagas and /graph returns visual DAG representation")
    void testGetSagaListAndGraphProjection() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "test-tenant-graph";
        String idempotencyKey = "key-graph-" + UUID.randomUUID();

        // 1. Create a saga instance with 3 steps
        journalStore.createSagaInstance(sagaId, tenantId, idempotencyKey, "account-transfer", "{\"amount\":100}");
        SagaStep step0 = journalStore.recordStepIntent(sagaId, 0, "debit-source", "{\"account\":\"A\"}");
        journalStore.recordStepCompletion(sagaId, 0, "{\"status\":\"DEBITED\"}");

        SagaStep step1 = journalStore.recordStepIntent(sagaId, 1, "credit-target", "{\"account\":\"B\"}");
        journalStore.recordStepCompletion(sagaId, 1, "{\"status\":\"CREDITED\"}");

        SagaStep step2 = journalStore.recordStepIntent(sagaId, 2, "send-notification", "{\"notify\":\"true\"}");
        journalStore.recordStepCompletion(sagaId, 2, "{\"status\":\"NOTIFIED\"}");

        journalStore.completeSaga(sagaId, "{\"final\":\"COMPLETED\"}");

        // 2. Query /api/v1/dashboard/sagas
        ResponseEntity<List<SagaSummaryDto>> listResponse = restTemplate.exchange(
                "/api/v1/dashboard/sagas?tenantId=" + tenantId,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<SagaSummaryDto>>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SagaSummaryDto> sagas = listResponse.getBody();
        assertThat(sagas).isNotNull();
        assertThat(sagas).anyMatch(s -> s.sagaId().equals(sagaId) && "COMPLETED".equals(s.status()));

        // 3. Query /api/v1/dashboard/sagas/{sagaId}/graph
        ResponseEntity<SagaGraphDto> graphResponse = restTemplate.getForEntity(
                "/api/v1/dashboard/sagas/" + sagaId + "/graph",
                SagaGraphDto.class
        );

        assertThat(graphResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SagaGraphDto graph = graphResponse.getBody();
        assertThat(graph).isNotNull();
        assertThat(graph.sagaId()).isEqualTo(sagaId);
        assertThat(graph.sagaType()).isEqualTo("account-transfer");
        assertThat(graph.status()).isEqualTo("COMPLETED");
        assertThat(graph.nodes()).hasSize(3);

        // Verify node attributes
        assertThat(graph.nodes().get(0).id()).isEqualTo("step-0");
        assertThat(graph.nodes().get(0).stepName()).isEqualTo("debit-source");
        assertThat(graph.nodes().get(0).status()).isEqualTo("COMPLETED");

        assertThat(graph.nodes().get(1).id()).isEqualTo("step-1");
        assertThat(graph.nodes().get(1).stepName()).isEqualTo("credit-target");
        assertThat(graph.nodes().get(1).status()).isEqualTo("COMPLETED");

        // Verify edges
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges().get(0).source()).isEqualTo("step-0");
        assertThat(graph.edges().get(0).target()).isEqualTo("step-1");
        assertThat(graph.edges().get(1).source()).isEqualTo("step-1");
        assertThat(graph.edges().get(1).target()).isEqualTo("step-2");

        // 4. Unknown saga returns 404
        ResponseEntity<String> notFoundResponse = restTemplate.getForEntity(
                "/api/v1/dashboard/sagas/" + UUID.randomUUID() + "/graph",
                String.class
        );
        assertThat(notFoundResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
