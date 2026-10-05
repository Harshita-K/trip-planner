package com.wanderly.user;

import java.util.Set;

/** Read-only view of a user's taste, consumed by places, itinerary and recommendations. */
public record Preferences(Set<String> interests, String budgetLevel, TravelPace pace) {

    public static final Preferences DEFAULT = new Preferences(Set.of(), "mid", TravelPace.BALANCED);
}
