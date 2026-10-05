package com.wanderly.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin JSON-over-Kafka publisher, fire-and-forget: sends are asynchronous and failures are only
 * logged. Used where losing a message is acceptable (searches and views) or where the source of
 * truth isn't Postgres (Redis codes, self-healing reminders). Messages that describe a database change
 * go through the transactional outbox instead ({@link com.wanderly.messaging.outbox.Outbox}, D56).
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
        publish(Topics.USER_ACTIVITY, userId == null ? null : userId.toString(),
                ActivityEvent.of(userId, eventType, itemType, itemId, category, city));
    }
}
