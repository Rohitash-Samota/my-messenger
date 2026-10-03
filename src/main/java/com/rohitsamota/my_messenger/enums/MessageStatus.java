package com.rohitsamota.my_messenger.enums;

public enum MessageStatus {
    SENT("SENT"),
    DELIVERED("DELIVERED"),
    READ("READ"),
    FAILED("FAILED");

    private final String value;

    MessageStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
