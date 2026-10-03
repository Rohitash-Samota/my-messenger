package com.rohitsamota.my_messenger.services;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rohitsamota.my_messenger.repo.ProcessedEventRepository;

@Service
public class ProcessedEventService {
    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Claims the event and executes the work in one database transaction. If
     * work fails, the claim rolls back so Kafka redelivery can retry it.
     *
     * @return true when work ran, false when this consumer already processed it
     */
    @Transactional
    public boolean processOnce(String consumerName, UUID eventId, Runnable work) {
        validate(consumerName, eventId);
        Objects.requireNonNull(work, "work is required");
        int inserted = processedEventRepository.insertIfAbsent(
                consumerName,
                eventId.toString(),
                Instant.now());
        if (inserted == 0) {
            return false;
        }
        work.run();
        return true;
    }

    /**
     * Value-returning variant of {@link #processOnce(String, UUID, Runnable)}.
     * An empty result means the event was already processed.
     */
    @Transactional
    public <T> Optional<T> processOnce(String consumerName, UUID eventId, Supplier<T> work) {
        validate(consumerName, eventId);
        Objects.requireNonNull(work, "work is required");
        int inserted = processedEventRepository.insertIfAbsent(
                consumerName,
                eventId.toString(),
                Instant.now());
        if (inserted == 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(work.get());
    }

    private void validate(String consumerName, UUID eventId) {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName is required");
        }
        if (consumerName.length() > 100) {
            throw new IllegalArgumentException("consumerName must not exceed 100 characters");
        }
        Objects.requireNonNull(eventId, "eventId is required");
    }
}
