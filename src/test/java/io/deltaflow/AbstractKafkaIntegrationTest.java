package io.deltaflow;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;

public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    public static final RedpandaContainer redpanda;

    static {
        redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:latest");
        redpanda.start();
    }

    @DynamicPropertySource
    static void configureKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
        registry.add("deltaflow.kafka.enabled", () -> "true");
        registry.add("deltaflow.kafka.domain-events-topic", () -> "deltaflow.domain-events");
        registry.add("deltaflow.kafka.dlq-topic", () -> "deltaflow.domain-events.dlq");
    }
}
