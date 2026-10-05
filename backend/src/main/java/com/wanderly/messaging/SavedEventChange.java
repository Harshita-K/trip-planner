package com.wanderly.messaging;

import java.time.Instant;
import java.util.UUID;

/** Payload on the {@code saved-events} topic: a user saved an event to their plans, or removed it. */
public record SavedEventChange(
        String eventId,            // unique per message: the consumers' idempotency key
        String eventType,          // event.saved | event.unsaved
        Instant occurredAt,
        UUID userId,
        UUID itemId,               // the saved event's id
        String itemTitle,
        String category,
        Instant startsAt,
        Location location) {

    public static final String SAVED = "event.saved";
    public static final String UNSAVED = "event.unsaved";

    public record Location(double lat, double lng, String city) {
    }
}
