package com.rohitsamota.my_messenger.config;

import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.rohitsamota.my_messenger.messaging.KafkaTopics;

import tools.jackson.core.JacksonException;

@Configuration
@EnableScheduling
public class KafkaReliabilityConfig {

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> stringKafkaProducerFactory(KafkaProperties properties) {
        Map<String, Object> producerProperties = properties.buildProducerProperties();
        return new DefaultKafkaProducerFactory<>(producerProperties);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.retry.max-attempts:4}") int maxDeliveryAttempts,
            @Value("${app.kafka.retry.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${app.kafka.retry.multiplier:2.0}") double multiplier,
            @Value("${app.kafka.retry.max-interval-ms:10000}") long maxIntervalMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        KafkaTopics.deadLetterTopicFor(record.topic()),
                        record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setVerifyPartition(true);
        recoverer.setAppendOriginalHeaders(true);

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(Math.max(0, maxDeliveryAttempts - 1));
        backOff.setInitialInterval(Math.max(1, initialIntervalMs));
        backOff.setMultiplier(Math.max(1.0, multiplier));
        backOff.setMaxInterval(Math.max(initialIntervalMs, maxIntervalMs));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                JacksonException.class);
        errorHandler.setAckAfterHandle(true);
        return errorHandler;
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.kafka.topics.create",
            havingValue = "true",
            matchIfMissing = true)
    public NewTopic conversationEventsTopic(
            @Value("${app.kafka.topics.partitions:6}") int partitions,
            @Value("${app.kafka.topics.replication-factor:1}") int replicationFactor,
            @Value("${app.kafka.topics.min-in-sync-replicas:1}") int minInSyncReplicas,
            @Value("${app.kafka.topics.retention-ms:604800000}") long retentionMs) {
        return eventTopic(
                KafkaTopics.CONVERSATION_EVENTS,
                partitions,
                replicationFactor,
                minInSyncReplicas,
                retentionMs);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.kafka.topics.create",
            havingValue = "true",
            matchIfMissing = true)
    public NewTopic notificationEventsTopic(
            @Value("${app.kafka.topics.partitions:6}") int partitions,
            @Value("${app.kafka.topics.replication-factor:1}") int replicationFactor,
            @Value("${app.kafka.topics.min-in-sync-replicas:1}") int minInSyncReplicas,
            @Value("${app.kafka.topics.retention-ms:604800000}") long retentionMs) {
        return eventTopic(
                KafkaTopics.NOTIFICATION_EVENTS,
                partitions,
                replicationFactor,
                minInSyncReplicas,
                retentionMs);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.kafka.topics.create",
            havingValue = "true",
            matchIfMissing = true)
    public NewTopic conversationEventsDltTopic(
            @Value("${app.kafka.topics.partitions:6}") int partitions,
            @Value("${app.kafka.topics.replication-factor:1}") int replicationFactor,
            @Value("${app.kafka.topics.min-in-sync-replicas:1}") int minInSyncReplicas,
            @Value("${app.kafka.topics.dlt-retention-ms:2592000000}") long retentionMs) {
        return eventTopic(
                KafkaTopics.CONVERSATION_EVENTS_DLT,
                partitions,
                replicationFactor,
                minInSyncReplicas,
                retentionMs);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.kafka.topics.create",
            havingValue = "true",
            matchIfMissing = true)
    public NewTopic notificationEventsDltTopic(
            @Value("${app.kafka.topics.partitions:6}") int partitions,
            @Value("${app.kafka.topics.replication-factor:1}") int replicationFactor,
            @Value("${app.kafka.topics.min-in-sync-replicas:1}") int minInSyncReplicas,
            @Value("${app.kafka.topics.dlt-retention-ms:2592000000}") long retentionMs) {
        return eventTopic(
                KafkaTopics.NOTIFICATION_EVENTS_DLT,
                partitions,
                replicationFactor,
                minInSyncReplicas,
                retentionMs);
    }

    private NewTopic eventTopic(
            String name,
            int partitions,
            int replicationFactor,
            int minInSyncReplicas,
            long retentionMs) {
        if (partitions < 1) {
            throw new IllegalArgumentException("Kafka topic partitions must be positive");
        }
        if (replicationFactor < 1 || replicationFactor > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka replication factor is out of range");
        }
        if (minInSyncReplicas < 1 || minInSyncReplicas > replicationFactor) {
            throw new IllegalArgumentException("Kafka min ISR must be between 1 and the replication factor");
        }

        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minInSyncReplicas))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs))
                .build();
    }
}
