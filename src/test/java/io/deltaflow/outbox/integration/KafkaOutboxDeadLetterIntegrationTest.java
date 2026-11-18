package io.deltaflow.outbox.integration;

import io.deltaflow.AbstractKafkaIntegrationTest;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.KafkaDomainEventPublisher;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

public class KafkaOutboxDeadLetterIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @SpyBean
    private KafkaDomainEventPublisher kafkaDomainEventPublisher;

    private Consumer<String, String> dlqConsumer;

    @BeforeEach
    void setUpDlqConsumer() {
        outboxEventRepository.deleteAll();
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        dlqConsumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        dlqConsumer.subscribe(Collections.singletonList("deltaflow.domain-events.dlq"));
    }

    @AfterEach
    void tearDownConsumer() {
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
    }

    @Test
    @DisplayName("Poisoned outbox event exceeding max retries is safely routed to DLQ topic and marked DEAD_LETTER")
    void testDeadLetterQueueRoutingOnRetryExhaustion() {
        UUID eventId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();

        OutboxEvent event = new OutboxEvent(
                eventId,
                "SAGA_INSTANCE",
                sagaId,
                "SAGA_STARTED",
                "{\"error\":\"simulated_poison_pill\"}"
        );
        // Pre-set retry count to 4 (maxRetries is 5)
        event.setRetryCount(4);
        outboxEventRepository.save(event);

        // Simulate broker failure on publish()
        doThrow(new RuntimeException("Simulated persistent broker transport timeout"))
                .when(kafkaDomainEventPublisher).publish(any());

        outboxPublisher.runPublishingScan();

        // Verify database entity is marked DEAD_LETTER
        OutboxEvent dlqEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(dlqEvent.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER.name());
        assertThat(dlqEvent.getRetryCount()).isEqualTo(5);
        assertThat(dlqEvent.getErrorDetails()).contains("Simulated persistent broker transport timeout");

        // Verify event was dispatched to the Kafka DLQ topic
        ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        ConsumerRecord<String, String> dlqRecord = null;
        for (ConsumerRecord<String, String> rec : records) {
            if (sagaId.equals(rec.key())) {
                dlqRecord = rec;
                break;
            }
        }

        assertThat(dlqRecord).isNotNull();
        assertThat(dlqRecord.value()).contains("simulated_poison_pill");

        Map<String, String> headers = new HashMap<>();
        for (Header h : dlqRecord.headers()) {
            headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
        }

        assertThat(headers.get("X-Dead-Letter-Reason")).contains("Simulated persistent broker transport timeout");
    }
}
