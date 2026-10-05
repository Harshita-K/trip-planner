package com.wanderly.itinerary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "itineraries")
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String destination;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", columnDefinition = "jsonb", nullable = false)
    private ItineraryPlan plan;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Itinerary() {
    }

    public Itinerary(UUID userId, String destination, LocalDate startDate, LocalDate endDate, ItineraryPlan plan) {
        this.userId = userId;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.plan = plan;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDestination() {
        return destination;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public ItineraryPlan getPlan() {
        return plan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
