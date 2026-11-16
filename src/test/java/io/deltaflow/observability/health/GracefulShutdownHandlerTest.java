package io.deltaflow.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

public class GracefulShutdownHandlerTest {

    @Test
    @DisplayName("Should track active sagas and drain them within timeout")
    void shouldTrackAndDrainActiveSagas() {
        GracefulShutdownHandler handler = new GracefulShutdownHandler();

        assertThat(handler.getActiveSagaCount()).isEqualTo(0);
        assertThat(handler.isDraining()).isFalse();

        handler.registerSagaStart();
        handler.registerSagaStart();
        assertThat(handler.getActiveSagaCount()).isEqualTo(2);

        // Health should be UP initially
        Health initialHealth = handler.health();
        assertThat(initialHealth.getStatus()).isEqualTo(Status.UP);

        // Async task finishes the sagas
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}
                handler.registerSagaEnd();
                handler.registerSagaEnd();
            });

            boolean drained = handler.drain(Duration.ofSeconds(2));
            assertThat(drained).isTrue();
            assertThat(handler.getActiveSagaCount()).isEqualTo(0);
            assertThat(handler.isDraining()).isTrue();

            // After draining, health returns OUT_OF_SERVICE
            Health drainingHealth = handler.health();
            assertThat(drainingHealth.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        }
    }
}
