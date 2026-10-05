package com.wanderly.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
public class UserPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "interests", columnDefinition = "text[]", nullable = false)
    private List<String> interests = new ArrayList<>();

    @Column(name = "budget_level", nullable = false)
    private String budgetLevel = "mid";

    @Column(name = "travel_pace", nullable = false)
    private String travelPace = TravelPace.BALANCED.value();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserPreferences() {
    }

    public UserPreferences(UUID userId) {
        this.userId = userId;
    }

    public void update(List<String> interests, String budgetLevel, TravelPace pace) {
        this.interests = new ArrayList<>(interests);
        this.budgetLevel = budgetLevel;
        this.travelPace = pace.value();
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public List<String> getInterests() {
        return interests;
    }

    public String getBudgetLevel() {
        return budgetLevel;
    }

    public TravelPace getTravelPace() {
        return TravelPace.from(travelPace);
    }
}
