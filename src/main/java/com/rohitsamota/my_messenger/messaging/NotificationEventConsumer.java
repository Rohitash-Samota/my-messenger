package com.rohitsamota.my_messenger.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.event.NotificationPayload;
import com.rohitsamota.my_messenger.services.NotificationService;
import com.rohitsamota.my_messenger.services.ProcessedEventService;

import tools.jackson.core.type.TypeReference;

@Component
public class NotificationEventConsumer {
    private static final String CONSUMER_NAME = "in-app-notification-v1";

    private final EventEnvelopeReader envelopeReader;
    private final ProcessedEventService processedEventService;
    private final NotificationService notificationService;

    public NotificationEventConsumer(
            EventEnvelopeReader envelopeReader,
            ProcessedEventService processedEventService,
            NotificationService notificationService) {
        this.envelopeReader = envelopeReader;
        this.processedEventService = processedEventService;
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = KafkaTopics.NOTIFICATION_EVENTS,
            groupId = "${app.kafka.consumer-groups.in-app-notification:in-app-notification-v1}")
    public void receive(String value) {
        if (!EventTypes.NOTIFICATION_REQUESTED.equals(envelopeReader.eventType(value))) {
            throw new IllegalArgumentException("Unsupported notification event type");
        }
        EventEnvelope<NotificationPayload> envelope = envelopeReader.read(
                value,
                new TypeReference<EventEnvelope<NotificationPayload>>() { });
        processedEventService.processOnce(
                CONSUMER_NAME,
                envelope.eventId(),
                () -> notificationService.store(envelope.eventId(), envelope.payload()));
    }
}
