package com.rohitsamota.my_messenger.messaging.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rohitsamota.my_messenger.entity.OutboxEvent;
import com.rohitsamota.my_messenger.repo.OutboxEventRepository;

@Service
public class OutboxRelayStore {
    private final OutboxEventRepository outboxEventRepository;

    public OutboxRelayStore(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(
            String claimToken,
            int batchSize,
            int maxAttempts,
            Instant now,
            Duration leaseDuration) {
        outboxEventRepository.claimBatch(
                claimToken,
                now,
                now.plus(leaseDuration),
                maxAttempts,
                batchSize);
        return List.copyOf(outboxEventRepository.findByLockOwnerOrderByIdAsc(claimToken));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(OutboxEvent event, String claimToken, Instant publishedAt) {
        return outboxEventRepository.markPublished(
                event.getEventIdValue(),
                claimToken,
                publishedAt) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            OutboxEvent event,
            String claimToken,
            Instant failedAt,
            Instant availableAt,
            String lastError,
            int maxAttempts) {
        return outboxEventRepository.markFailed(
                event.getEventIdValue(),
                claimToken,
                failedAt,
                availableAt,
                lastError,
                maxAttempts) == 1;
    }
}
