package com.wanderly.common;

public record GeoPoint(double lat, double lng) {

    /** Great-circle distance in kilometres (haversine). */
    public double distanceKm(GeoPoint other) {
        return Geo.haversineKm(lat, lng, other.lat, other.lng);
    }
}
