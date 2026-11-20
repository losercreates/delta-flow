package io.deltaflow.dashboard.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.dashboard.dto.DashboardLiveEvent;
import io.deltaflow.dashboard.dto.TelemetrySummaryDto;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DashboardStreamIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DashboardEventBroadcaster eventBroadcaster;

    @Test
    @DisplayName("US4: GET /api/v1/dashboard/telemetry returns system metrics and circuit breaker status")
    void testGetTelemetry() {
        ResponseEntity<TelemetrySummaryDto> response = restTemplate.getForEntity(
                "/api/v1/dashboard/telemetry",
                TelemetrySummaryDto.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TelemetrySummaryDto telemetry = response.getBody();
        assertThat(telemetry).isNotNull();
        assertThat(telemetry.activeSagas()).isGreaterThanOrEqualTo(0);
        assertThat(telemetry.pendingOutboxEvents()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("US4: SSE stream subscription and live event broadcast")
    void testSseStreamSubscriptionAndBroadcast() {
        int initialSubscribers = eventBroadcaster.getSubscriberCount();

        // Subscribe an emitter
        SseEmitter emitter = eventBroadcaster.subscribe();
        assertThat(eventBroadcaster.getSubscriberCount()).isEqualTo(initialSubscribers + 1);

        // Broadcast saga state transitions
        UUID sagaId = UUID.randomUUID();
        DashboardLiveEvent liveEvent = DashboardLiveEvent.of(
                "SAGA_STATE_CHANGED",
                sagaId,
                "default",
                Map.of("status", "EXECUTING")
        );

        eventBroadcaster.broadcast(liveEvent);

        // Broadcast suspended event
        DashboardLiveEvent suspendedEvent = DashboardLiveEvent.of(
                "SAGA_SUSPENDED",
                sagaId,
                "default",
                Map.of("status", "SUSPENDED", "awaitingEvent", "3DS_VERIFICATION")
        );
        eventBroadcaster.broadcast(suspendedEvent);

        // Broadcast resumed event
        DashboardLiveEvent resumedEvent = DashboardLiveEvent.of(
                "SAGA_RESUMED",
                sagaId,
                "default",
                Map.of("status", "EXECUTING")
        );
        eventBroadcaster.broadcast(resumedEvent);

        // Broadcast heartbeat
        eventBroadcaster.sendHeartbeat();

        // Cleanup
        emitter.complete();
    }
}
