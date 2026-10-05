package com.wanderly.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.messaging.NotificationRequest;
import com.wanderly.messaging.SavedEventChange;
import com.wanderly.messaging.Topics;
import com.wanderly.recommendation.NearbySuggestionService;
import com.wanderly.recommendation.RankedPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consumer group {@code notification-service}. Turns saves and notification requests into user
 * messages. Failures are retried and then dead-lettered by the shared error handler.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    /** All seeded destinations are in India; per-user time zones would replace this. */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a 'IST'", Locale.ENGLISH).withZone(DISPLAY_ZONE);

    private final ObjectMapper mapper;
    private final NotificationService notifications;
    private final NearbySuggestionService nearby;

    public NotificationConsumer(ObjectMapper mapper, NotificationService notifications,
                                NearbySuggestionService nearby) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.nearby = nearby;
    }

    /** A save gets an inbox note (no email: the user just did it) with F7 picks around the venue. */
    @KafkaListener(topics = Topics.SAVED_EVENTS, groupId = "notification-service")
    public void onSavedEvent(String payload) throws JsonProcessingException {
        SavedEventChange change = mapper.readValue(payload, SavedEventChange.class);
        if (!SavedEventChange.SAVED.equals(change.eventType())) {
            log.debug("Ignoring saved-event change {}", change.eventType());
            return;
        }
        deliver(change.userId(), change.eventType(), "Saved: " + change.itemTitle(), savedBody(change),
                change.eventId(), false);
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS, groupId = "notification-service")
    public void onNotificationRequest(String payload) throws JsonProcessingException {
        NotificationRequest request = mapper.readValue(payload, NotificationRequest.class);
        deliver(request.userId(), request.type(), request.subject(), request.body(), request.eventId(), true);
    }

    private void deliver(UUID userId, String type, String subject, String body, String eventId, boolean email) {
        try {
            notifications.deliver(userId, type, subject, body, eventId, email);
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent copy of the same event won the unique constraint: already delivered.
            log.debug("Duplicate event {} lost the race; skipping", eventId);
        }
    }

    private String savedBody(SavedEventChange change) {
        StringBuilder body = new StringBuilder().append(change.itemTitle()).append(" is in your plans.");
        if (change.startsAt() != null) {
            body.append("\nWhen: ").append(when(change.startsAt()))
                    .append("\nWe'll remind you the day before.");
        }
        if (change.location() != null) {
            NearbySuggestionService.NearbySuggestions picks =
                    nearby.around(change.userId(), change.location().lat(), change.location().lng());
            appendPicks(body, "Eat nearby", picks.food());
            appendPicks(body, "While you're there", picks.attractions());
        }
        return body.toString();
    }

    static String when(Instant instant) {
        return WHEN.format(instant).replace("AM", "am").replace("PM", "pm");
    }

    private static void appendPicks(StringBuilder body, String heading, List<RankedPlace> picks) {
        if (picks.isEmpty()) {
            return;
        }
        body.append("\n\n").append(heading).append(":\n").append(picks.stream().limit(3)
                .map(p -> "• %s (%.1f km away, rated %.1f)".formatted(p.place().name(), p.place().distanceKm(),
                        p.place().rating()))
                .collect(Collectors.joining("\n")));
    }
}
