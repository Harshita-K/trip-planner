package com.wanderly.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Consumer group {@code recommendation-service} on {@code user-activity}. Runs independently of
 * the notification consumer: same events, separate offsets, separately scalable.
 *
 * <p>Not strictly idempotent (a replay adds weight twice); acceptable because affinity is a soft,
 * decaying signal. Contrast with notifications, where duplicates are user-visible and deduped.
 */
@Component
public class ActivityConsumer {

    static final Map<String, Double> WEIGHTS = Map.of(
            ActivityEvent.PLACES_SEARCHED, 0.5,
            ActivityEvent.EVENT_VIEWED, 1.0,
            ActivityEvent.ITINERARY_GENERATED, 2.0,
            ActivityEvent.EVENT_SAVED, 5.0);

    private final ObjectMapper mapper;
    private final AffinityStore affinity;

    public ActivityConsumer(ObjectMapper mapper, AffinityStore affinity) {
        this.mapper = mapper;
        this.affinity = affinity;
    }

    @KafkaListener(topics = Topics.USER_ACTIVITY, groupId = "recommendation-service")
    public void onActivity(String payload) throws JsonProcessingException {
        ActivityEvent event = mapper.readValue(payload, ActivityEvent.class);
        if (event.userId() == null || event.category() == null || event.category().isBlank()) {
            return;
        }
        double weight = WEIGHTS.getOrDefault(event.eventType(), 0.0);
        if (weight > 0) {
            affinity.increment(event.userId(), event.category().toLowerCase(Locale.ROOT), weight);
        }
    }
}
