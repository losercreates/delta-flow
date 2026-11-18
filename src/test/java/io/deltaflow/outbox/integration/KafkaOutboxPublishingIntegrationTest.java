package io.deltaflow.outbox.integration;

import io.deltaflow.AbstractKafkaIntegrationTest;
import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.OutboxPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class KafkaOutboxPublishingIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        outboxEventRepository.deleteAll();
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(Collections.singletonList("deltaflow.domain-events"));
    }

    @AfterEach
    void tearDownConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("Outbox publisher streams events to Kafka topic with aggregate partitioning and W3C traceparent headers")
    void testKafkaOutboxPublishingWithPartitioningAndTraceparent() {
        UUID eventId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanId = "00f067aa0ba902b7";
        String expectedTraceparent = "00-" + traceId + "-" + spanId + "-01";

        TraceContextHolder.set(io.deltaflow.observability.tracing.TraceContextMetadata.create(traceId, spanId, null, "01"));

        OutboxEvent event = new OutboxEvent(
                eventId,
                "SAGA_INSTANCE",
                sagaId,
                "SAGA_COMPLETED",
                "{\"status\":\"COMPLETED\",\"amount\":250.00}"
        );
        event.setPartitionKey(sagaId);
        outboxEventRepository.save(event);

        try {
            int dispatched = outboxPublisher.runPublishingScan();
            assertThat(dispatched).isGreaterThanOrEqualTo(1);

            // Assert database status transitioned to PUBLISHED
            OutboxEvent saved = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
            assertThat(saved.getPublishedAt()).isNotNull();

            // Poll Kafka record
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();

            ConsumerRecord<String, String> matchingRecord = null;
            for (ConsumerRecord<String, String> rec : records) {
                if (sagaId.equals(rec.key())) {
                    matchingRecord = rec;
                    break;
                }
            }

            assertThat(matchingRecord).isNotNull();
            assertThat(matchingRecord.key()).isEqualTo(sagaId);
            assertThat(matchingRecord.value()).contains("COMPLETED");

            // Verify Headers
            Map<String, String> headerMap = new HashMap<>();
            for (Header h : matchingRecord.headers()) {
                headerMap.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
            }

            assertThat(headerMap.get("X-Event-Id")).isEqualTo(eventId.toString());
            assertThat(headerMap.get("X-Event-Type")).isEqualTo("SAGA_COMPLETED");
            assertThat(headerMap.get("X-Aggregate-Id")).isEqualTo(sagaId);
            assertThat(headerMap.get("traceparent")).isEqualTo(expectedTraceparent);
        } finally {
            TraceContextHolder.clear();
        }
    }
}
