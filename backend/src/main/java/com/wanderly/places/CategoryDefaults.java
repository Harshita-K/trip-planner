package com.wanderly.places;

import java.time.LocalTime;
import java.util.Map;

/** Fallbacks for live places that lack data: typical visit length and opening hours per category. */
final class CategoryDefaults {

    private static final Map<String, Integer> VISIT_MINUTES = Map.of(
            "museum", 90, "history", 75, "nature", 60, "religious", 30,
            "food", 60, "shopping", 60, "amusement", 180, "landmark", 30, "nightlife", 90);

    /** Shown to users as "Hours estimated". */
    private static final Map<String, LocalTime[]> HOURS = Map.of(
            "museum", hours(10, 0, 17, 0), "history", hours(9, 0, 17, 30), "nature", hours(6, 0, 19, 0),
            "religious", hours(6, 0, 20, 0), "food", hours(11, 0, 22, 0), "shopping", hours(10, 0, 21, 0),
            "amusement", hours(10, 0, 18, 0), "landmark", hours(0, 0, 23, 59), "nightlife", hours(18, 0, 23, 30));

    private CategoryDefaults() {
    }

    static int visitMinutes(String category) {
        return VISIT_MINUTES.getOrDefault(category, 60);
    }

    static LocalTime[] hours(String category) {
        return HOURS.getOrDefault(category, hours(9, 0, 18, 0));
    }

    private static LocalTime[] hours(int oh, int om, int ch, int cm) {
        return new LocalTime[]{LocalTime.of(oh, om), LocalTime.of(ch, cm)};
    }
}
