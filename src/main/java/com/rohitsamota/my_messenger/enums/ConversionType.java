package com.rohitsamota.my_messenger.enums;

public enum ConversionType {
    INDIVIDUAL("individual"),
    GROUP("group");

    private final String value;

    ConversionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
