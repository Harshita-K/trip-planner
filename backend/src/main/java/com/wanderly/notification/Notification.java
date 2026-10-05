package com.wanderly.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private String type;
    private String channel;
    private String subject;
    private String body;

    @Column(name = "source_event_id", nullable = false, unique = true)
    private String sourceEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {
    }

    public Notification(UUID userId, String type, String channel, String subject, String body, String sourceEventId) {
        this.userId = userId;
        this.type = type;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.sourceEventId = sourceEventId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
