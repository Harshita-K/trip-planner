package com.wanderly.places;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.wanderly.common.GeoPoint;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/**
 * A point of interest (attraction, restaurant, ...). Provider-agnostic: the curated dataset and
 * OpenTripMap are both normalised into this shape. {@code distanceKm} is relative to the query.
 *
 * <p>{@code opens}/{@code closes} is the typical daily window and {@code closedOn} the weekly closing
 * days. {@code hoursSource} says how far to trust them: {@code curated} (hand-checked), {@code osm}
 * (OpenStreetMap opening_hours) or {@code estimated} (category default; no data found).
 */
public record Place(
        String id,
        String name,
        String category,
        double lat,
        double lng,
        double rating,          // 0..5
        int visitMinutes,       // typical time spent there
        @JsonFormat(pattern = "HH:mm") LocalTime opens,
        @JsonFormat(pattern = "HH:mm") LocalTime closes,
        String city,
        double distanceKm,
        Set<DayOfWeek> closedOn,
        String hoursSource) {

    public static final String CURATED = "curated";
    public static final String OSM = "osm";
    public static final String ESTIMATED = "estimated";

    public Place {
        closedOn = closedOn == null ? Set.of() : Set.copyOf(closedOn);
        hoursSource = hoursSource == null ? CURATED : hoursSource;
    }

    /** Curated places: open every day. */
    public Place(String id, String name, String category, double lat, double lng, double rating, int visitMinutes,
                 LocalTime opens, LocalTime closes, String city, double distanceKm) {
        this(id, name, category, lat, lng, rating, visitMinutes, opens, closes, city, distanceKm, Set.of(), CURATED);
    }

    public GeoPoint point() {
        return new GeoPoint(lat, lng);
    }

    public boolean isOpenOn(DayOfWeek day) {
        return !closedOn.contains(day);
    }

    public Place withDistanceFrom(GeoPoint origin) {
        double km = Math.round(origin.distanceKm(point()) * 100.0) / 100.0;
        return new Place(id, name, category, lat, lng, rating, visitMinutes, opens, closes, city, km, closedOn, hoursSource);
    }

    public Place withCity(String city) {
        return new Place(id, name, category, lat, lng, rating, visitMinutes, opens, closes, city, distanceKm, closedOn, hoursSource);
    }

    public Place withHours(LocalTime opens, LocalTime closes, Set<DayOfWeek> closedOn, String hoursSource) {
        return new Place(id, name, category, lat, lng, rating, visitMinutes, opens, closes, city, distanceKm, closedOn, hoursSource);
    }
}
