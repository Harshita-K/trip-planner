package com.wanderly.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String destination;
    private double lat;
    private double lng;

    @Column(name = "duration_days")
    private int durationDays;

    private String description;

    protected Trip() {
    }

    public UUID getId() {
        return id;
    }

    public String getDestination() {
        return destination;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public String getDescription() {
        return description;
    }
}
