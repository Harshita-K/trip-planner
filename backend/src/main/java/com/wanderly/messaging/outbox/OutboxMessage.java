package com.wanderly.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One Kafka message waiting to be relayed (or already relayed, until cleanup). */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "message_key")
    private String key;

    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxMessage() {
    }

    OutboxMessage(String topic, String key, String payload) {
        this.topic = topic;
        this.key = key;
        this.payload = payload;
    }

    void markPublished() {
        this.publishedAt = Instant.now();
        this.attempts++;
        this.lastError = null;
    }

    void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
