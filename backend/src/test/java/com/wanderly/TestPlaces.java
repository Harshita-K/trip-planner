package com.wanderly;

import com.wanderly.places.Place;

import java.time.LocalTime;

public final class TestPlaces {

    private TestPlaces() {
    }

    public static Place place(String id, String category, double lat, double lng, double rating) {
        return place(id, category, lat, lng, rating, 60, "08:00", "20:00");
    }

    public static Place place(String id, String category, double lat, double lng, double rating,
                              int visitMinutes, String opens, String closes) {
        return new Place(id, id, category, lat, lng, rating, visitMinutes, LocalTime.parse(opens),
                LocalTime.parse(closes), "Test City", 0);
    }
}
