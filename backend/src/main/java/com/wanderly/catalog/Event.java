package com.wanderly.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String title;
    private String category;
    private String venue;
    private double lat;
    private double lng;
    private String city;

    @Column(name = "start_time")
    private Instant startTime;

    /** Indicative entry fee, for planning; 0 = free. Nothing is sold here. */
    private BigDecimal price;
    private String currency;
    private String description;

    /** Who listed it; null for the seeded catalogue. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Event() {
    }

    public Event(UUID createdBy, String title, String category, String venue, String city, double lat, double lng,
                 Instant startTime, BigDecimal price, String description) {
        this.createdBy = createdBy;
        this.currency = "INR";
        update(title, category, venue, city, lat, lng, startTime, price, description);
    }

    public void update(String title, String category, String venue, String city, double lat, double lng,
                       Instant startTime, BigDecimal price, String description) {
        this.title = title;
        this.category = category;
        this.venue = venue;
        this.city = city;
        this.lat = lat;
        this.lng = lng;
        this.startTime = startTime;
        this.price = price;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getVenue() {
        return venue;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public String getCity() {
        return city;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
