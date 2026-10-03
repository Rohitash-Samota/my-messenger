package com.rohitsamota.my_messenger.messaging;

import java.util.Set;

public final class KafkaTopics {
    public static final String CONVERSATION_EVENTS = "conversation-events.v1";
    public static final String NOTIFICATION_EVENTS = "notification-events.v1";
    public static final String CONVERSATION_EVENTS_DLT = CONVERSATION_EVENTS + ".DLT";
    public static final String NOTIFICATION_EVENTS_DLT = NOTIFICATION_EVENTS + ".DLT";

    private static final Set<String> PUBLISHABLE_TOPICS = Set.of(
            CONVERSATION_EVENTS,
            NOTIFICATION_EVENTS);

    private KafkaTopics() {
    }

    public static boolean isPublishable(String topic) {
        return PUBLISHABLE_TOPICS.contains(topic);
    }

    public static String deadLetterTopicFor(String topic) {
        return switch (topic) {
            case CONVERSATION_EVENTS -> CONVERSATION_EVENTS_DLT;
            case NOTIFICATION_EVENTS -> NOTIFICATION_EVENTS_DLT;
            default -> topic + ".DLT";
        };
    }
}
