package com.wanderly.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload on the {@code user-activity} topic. Deliberately generic: every service emits these,
 * and downstream consumers (recommendations now, the S3 sink later) pick what they need.
 */
public record ActivityEvent(
        String eventId,
        String eventType,     // event.viewed | places.searched | itinerary.generated | event.saved | travel.searched | hotels.searched
        Instant occurredAt,
        UUID userId,          // null for anonymous visitors
        String itemType,
        String itemId,
        String category,
        String city) {

    public static final String EVENT_VIEWED = "event.viewed";
    public static final String PLACES_SEARCHED = "places.searched";
    public static final String ITINERARY_GENERATED = "itinerary.generated";
    public static final String EVENT_SAVED = "event.saved";
    public static final String TRAVEL_SEARCHED = "travel.searched";
    public static final String HOTELS_SEARCHED = "hotels.searched";
}
