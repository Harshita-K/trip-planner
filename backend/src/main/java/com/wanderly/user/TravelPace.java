package com.wanderly.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum TravelPace {
    RELAXED, BALANCED, PACKED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TravelPace from(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        return TravelPace.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
