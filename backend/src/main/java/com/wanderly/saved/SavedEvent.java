package com.wanderly.saved;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** "I'm planning to go": a user's bookmark on an event. Rows are inserted by {@link SavedEventRepository#insertIfAbsent}. */
@Entity
@Table(name = "saved_events")
public class SavedEvent {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedEvent() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
