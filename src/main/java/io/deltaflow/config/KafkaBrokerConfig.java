package io.deltaflow.config;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaBrokerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaBrokerConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${deltaflow.kafka.domain-events-topic:deltaflow.domain-events}")
    private String domainEventsTopic;

    @Value("${deltaflow.kafka.dlq-topic:deltaflow.domain-events.dlq}")
    private String dlqTopic;

    @Value("${deltaflow.kafka.partitions:3}")
    private int partitions;

    @Value("${deltaflow.kafka.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "deltaflow.kafka.enabled", havingValue = "true", matchIfMissing = false)
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }

    @Bean
    @ConditionalOnProperty(name = "deltaflow.kafka.enabled", havingValue = "true", matchIfMissing = false)
    public NewTopic domainEventsTopic() {
        log.info("Provisioning Kafka domain events topic: {} with {} partitions", domainEventsTopic, partitions);
        return new NewTopic(domainEventsTopic, partitions, replicationFactor);
    }

    @Bean
    @ConditionalOnProperty(name = "deltaflow.kafka.enabled", havingValue = "true", matchIfMissing = false)
    public NewTopic dlqTopic() {
        log.info("Provisioning Kafka DLQ topic: {} with {} partitions", dlqTopic, partitions);
        return new NewTopic(dlqTopic, partitions, replicationFactor);
    }
}
