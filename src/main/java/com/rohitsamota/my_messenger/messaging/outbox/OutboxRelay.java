package com.rohitsamota.my_messenger.messaging.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rohitsamota.my_messenger.entity.OutboxEvent;

@Component
@ConditionalOnProperty(
        name = "app.outbox.relay-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxRelayStore relayStore;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final Duration sendTimeout;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final double retryMultiplier;
    private final String instanceId;

    public OutboxRelay(
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxRelayStore relayStore,
            @Value("${app.outbox.batch-size:50}") int batchSize,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.outbox.lease-ms:120000}") long leaseMs,
            @Value("${app.outbox.send-timeout-ms:30000}") long sendTimeoutMs,
            @Value("${app.outbox.retry-initial-ms:1000}") long initialRetryMs,
            @Value("${app.outbox.retry-max-ms:300000}") long maxRetryMs,
            @Value("${app.outbox.retry-multiplier:2.0}") double retryMultiplier,
            @Value("${app.outbox.instance-id:local}") String configuredInstanceId) {
        this.kafkaTemplate = kafkaTemplate;
        this.relayStore = relayStore;
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");
        this.leaseDuration = Duration.ofMillis(requirePositive(leaseMs, "leaseMs"));
        this.sendTimeout = Duration.ofMillis(requirePositive(sendTimeoutMs, "sendTimeoutMs"));
        this.initialRetryDelay = Duration.ofMillis(requirePositive(initialRetryMs, "initialRetryMs"));
        this.maxRetryDelay = Duration.ofMillis(requirePositive(maxRetryMs, "maxRetryMs"));
        this.retryMultiplier = Math.max(1.0, retryMultiplier);
        this.instanceId = normalizeInstanceId(configuredInstanceId);
        if (leaseDuration.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException("Outbox lease must be longer than the Kafka send timeout");
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void publishPendingEvents() {
        String claimToken = instanceId + ":" + UUID.randomUUID();
        Instant now = Instant.now();
        List<OutboxEvent> claimed = relayStore.claimBatch(
                claimToken,
                batchSize,
                maxAttempts,
                now,
                leaseDuration);
        if (claimed.isEmpty()) {
            return;
        }

        long deadlineNanos = System.nanoTime() + sendTimeout.toNanos();
        List<PendingSend> pendingSends = new ArrayList<>(claimed.size());
        for (OutboxEvent event : claimed) {
            try {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new TimeoutException("Kafka send batch exceeded its timeout while dispatching");
                }
                CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                        event.getTopic(),
                        event.getMessageKey(),
                        event.getPayload());
                pendingSends.add(new PendingSend(event, future));
            } catch (RuntimeException | TimeoutException exception) {
                pendingSends.add(new PendingSend(event, CompletableFuture.failedFuture(exception)));
            }
        }

        for (PendingSend pendingSend : pendingSends) {
            publishResult(pendingSend, claimToken, deadlineNanos);
        }
    }

    private void publishResult(PendingSend pendingSend, String claimToken, long deadlineNanos) {
        OutboxEvent event = pendingSend.event();
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("Kafka send batch exceeded its timeout");
            }
            pendingSend.future().get(remainingNanos, TimeUnit.NANOSECONDS);
            boolean marked = relayStore.markPublished(event, claimToken, Instant.now());
            if (!marked) {
                log.warn("Outbox event {} was published after its claim was lost", event.getEventIdValue());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(event, claimToken, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            markFailed(event, claimToken, cause);
        } catch (TimeoutException exception) {
            markFailed(event, claimToken, exception);
        }
    }

    private void markFailed(OutboxEvent event, String claimToken, Throwable failure) {
        Instant failedAt = Instant.now();
        Duration delay = retryDelay(event.getAttemptCount());
        boolean marked = relayStore.markFailed(
                event,
                claimToken,
                failedAt,
                failedAt.plus(delay),
                errorMessage(failure),
                maxAttempts);
        if (!marked) {
            log.warn("Outbox event {} failed after its claim was lost", event.getEventIdValue());
            return;
        }
        int attempt = event.getAttemptCount() + 1;
        if (attempt >= maxAttempts) {
            log.error(
                    "Kafka publish permanently failed for outbox event {} after {} attempts",
                    event.getEventIdValue(),
                    attempt,
                    failure);
        } else {
            log.warn(
                    "Kafka publish failed for outbox event {} (attempt {}/{}); retry delay is {} ms",
                    event.getEventIdValue(),
                    attempt,
                    maxAttempts,
                    delay.toMillis(),
                    failure);
        }
    }

    private Duration retryDelay(int previousFailures) {
        double scaled = initialRetryDelay.toMillis()
                * Math.pow(retryMultiplier, Math.max(0, previousFailures));
        long delayMs = (long) Math.min(maxRetryDelay.toMillis(), scaled);
        return Duration.ofMillis(Math.max(1, delayMs));
    }

    private String errorMessage(Throwable failure) {
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private String normalizeInstanceId(String configuredInstanceId) {
        String value = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? "local"
                : configuredInstanceId.trim();
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private int requirePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private long requirePositive(long value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private record PendingSend(
            OutboxEvent event,
            CompletableFuture<SendResult<String, String>> future) {
    }
}
