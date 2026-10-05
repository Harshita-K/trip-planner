package com.wanderly.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Thin JSON-over-Kafka publisher. Sends are asynchronous; failures are logged rather than
 * surfaced to the user because every caller is on a path where the user should not wait on
 * Kafka. (A transactional outbox is the upgrade path if at-least-once publishing is required.)
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publish(String topic, String key, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserialisable event for " + topic, e);
        }
        kafka.send(topic, key, json).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to {} (key={}): {}", topic, key, ex.getMessage());
            }
        });
    }

    public void activity(UUID userId, String eventType, String itemType, String itemId, String category, String city) {
        ActivityEvent event = new ActivityEvent(UUID.randomUUID().toString(), eventType, Instant.now(),
                userId, itemType, itemId, category, city);
        publish(Topics.USER_ACTIVITY, userId == null ? null : userId.toString(), event);
    }
}
