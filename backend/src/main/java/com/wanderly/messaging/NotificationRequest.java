package com.wanderly.messaging;

import java.time.Instant;
import java.util.UUID;

/** Payload on the {@code notifications} topic: "please tell this user something". */
public record NotificationRequest(
        String eventId,       // deterministic for reminders so repeated scheduler runs dedupe
        String type,          // e.g. trip.reminder
        Instant occurredAt,
        UUID userId,
        String subject,
        String body) {
}
