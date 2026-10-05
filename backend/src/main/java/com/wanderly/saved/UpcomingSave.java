package com.wanderly.saved;

import java.time.Instant;
import java.util.UUID;

/** A saved event about to start: input for reminders. */
public record UpcomingSave(UUID userId, UUID eventId, String title, Instant startTime, String venue, String city) {
}
