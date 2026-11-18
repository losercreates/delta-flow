package io.deltaflow.outbox.service;

import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.outbox.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
@Primary
@ConditionalOnProperty(name = "deltaflow.kafka.enabled", havingValue = "true")
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${deltaflow.kafka.domain-events-topic:deltaflow.domain-events}")
    private String domainEventsTopic;

    @Value("${deltaflow.kafka.dlq-topic:deltaflow.domain-events.dlq}")
    private String dlqTopic;

    public KafkaDomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(OutboxEvent event) {
        String partitionKey = event.getPartitionKey() != null ? event.getPartitionKey() : event.getAggregateId();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                domainEventsTopic,
                partitionKey,
                event.getPayload()
        );

        injectHeaders(record, event);

        try {
            log.info("Dispatching outbox event [{}] to Kafka topic [{}] key [{}]",
                    event.getEventId(), domainEventsTopic, partitionKey);
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to publish outbox event [{}] to Kafka: {}", event.getEventId(), e.getMessage());
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Kafka dispatch error for event: " + event.getEventId(), e);
        }
    }

    public void publishToDeadLetterQueue(OutboxEvent event, String reason) {
        String partitionKey = event.getPartitionKey() != null ? event.getPartitionKey() : event.getAggregateId();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                dlqTopic,
                partitionKey,
                event.getPayload()
        );

        injectHeaders(record, event);
        if (reason != null) {
            record.headers().add(new RecordHeader("X-Dead-Letter-Reason", reason.getBytes(StandardCharsets.UTF_8)));
        }

        try {
            log.warn("Routing poisoned outbox event [{}] to DLQ topic [{}]", event.getEventId(), dlqTopic);
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to dispatch poisoned event [{}] to DLQ: {}", event.getEventId(), e.getMessage());
        }
    }

    private void injectHeaders(ProducerRecord<String, String> record, OutboxEvent event) {
        if (event.getEventId() != null) {
            record.headers().add(new RecordHeader("X-Event-Id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (event.getEventType() != null) {
            record.headers().add(new RecordHeader("X-Event-Type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
        }
        if (event.getAggregateType() != null) {
            record.headers().add(new RecordHeader("X-Aggregate-Type", event.getAggregateType().getBytes(StandardCharsets.UTF_8)));
        }
        if (event.getAggregateId() != null) {
            record.headers().add(new RecordHeader("X-Aggregate-Id", event.getAggregateId().getBytes(StandardCharsets.UTF_8)));
        }

        // Propagate OpenTelemetry traceparent if available
        String traceparent = TraceContextHolder.getTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            record.headers().add(new RecordHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
