package com.rohitsamota.my_messenger.enums;

public enum MessageType {
    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    DOCUMENT("document");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
