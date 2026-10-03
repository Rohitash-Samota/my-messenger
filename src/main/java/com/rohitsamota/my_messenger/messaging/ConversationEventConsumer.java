package com.rohitsamota.my_messenger.messaging;

import java.time.Instant;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.rohitsamota.my_messenger.event.ConversationCreatedPayload;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.event.MessageCreatedPayload;
import com.rohitsamota.my_messenger.event.MessageStateChangedPayload;
import com.rohitsamota.my_messenger.event.NotificationPayload;
import com.rohitsamota.my_messenger.services.OutboxService;
import com.rohitsamota.my_messenger.services.ProcessedEventService;

import tools.jackson.core.type.TypeReference;

@Component
public class ConversationEventConsumer {
    private static final String CONSUMER_NAME = "notification-fanout-v1";
    private static final int NOTIFICATION_PREVIEW_LENGTH = 160;

    private final EventEnvelopeReader envelopeReader;
    private final ProcessedEventService processedEventService;
    private final OutboxService outboxService;

    public ConversationEventConsumer(
            EventEnvelopeReader envelopeReader,
            ProcessedEventService processedEventService,
            OutboxService outboxService) {
        this.envelopeReader = envelopeReader;
        this.processedEventService = processedEventService;
        this.outboxService = outboxService;
    }

    @KafkaListener(
            topics = KafkaTopics.CONVERSATION_EVENTS,
            groupId = "${app.kafka.consumer-groups.notification-fanout:notification-fanout-v1}")
    public void receive(String value) {
        switch (envelopeReader.eventType(value)) {
            case EventTypes.CONVERSATION_CREATED -> consumeConversationCreated(value);
            case EventTypes.MESSAGE_CREATED -> fanOutNotifications(value);
            case EventTypes.MESSAGE_STATE_CHANGED -> consumeStateEvent(value);
            default -> throw new IllegalArgumentException("Unsupported conversation event type");
        }
    }

    private void consumeConversationCreated(String value) {
        EventEnvelope<ConversationCreatedPayload> envelope = envelopeReader.read(
                value,
                new TypeReference<EventEnvelope<ConversationCreatedPayload>>() { });
        processedEventService.processOnce(CONSUMER_NAME, envelope.eventId(), () -> {
            // The conversation and participant rows are already committed. This
            // durable event is retained for future cross-service projections.
        });
    }

    private void fanOutNotifications(String value) {
        EventEnvelope<MessageCreatedPayload> envelope = envelopeReader.read(
                value,
                new TypeReference<EventEnvelope<MessageCreatedPayload>>() { });
        processedEventService.processOnce(CONSUMER_NAME, envelope.eventId(), () -> {
            MessageCreatedPayload message = envelope.payload();
            for (Long recipientUserId : message.recipientUserIds()) {
                NotificationPayload notification = new NotificationPayload(
                        recipientUserId,
                        message.conversionId(),
                        message.messageId(),
                        message.senderUserId(),
                        "New message",
                        preview(message.content()),
                        Map.of(
                                "conversionId", message.conversionId().toString(),
                                "messageId", message.messageId().toString(),
                                "messageType", message.messageType().name()),
                        Instant.now());
                EventEnvelope<NotificationPayload> notificationEnvelope = EventEnvelope.v1(
                        EventTypes.NOTIFICATION_REQUESTED,
                        "NOTIFICATION",
                        recipientUserId + ":" + message.messageId(),
                        envelope.correlationId(),
                        notification);
                outboxService.enqueueNotificationEvent(recipientUserId, notificationEnvelope);
            }
        });
    }

    private void consumeStateEvent(String value) {
        EventEnvelope<MessageStateChangedPayload> envelope = envelopeReader.read(
                value,
                new TypeReference<EventEnvelope<MessageStateChangedPayload>>() { });
        processedEventService.processOnce(CONSUMER_NAME, envelope.eventId(), () -> {
            // State is already committed with the outbox row. Consuming it here keeps
            // this projection idempotent and leaves a clean extension point for
            // WebSocket/mobile receipt dispatch without changing receipt semantics.
        });
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "New attachment";
        }
        String normalized = content.strip();
        return normalized.length() <= NOTIFICATION_PREVIEW_LENGTH
                ? normalized
                : normalized.substring(0, NOTIFICATION_PREVIEW_LENGTH);
    }
}
