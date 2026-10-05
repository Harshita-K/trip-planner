package com.wanderly.travel;

import com.wanderly.common.GeoPoint;

/** An Indian airport with scheduled service (OurAirports open data, public domain). */
public record Airport(String iata, String name, String city, double lat, double lng, String size) {

    public GeoPoint point() {
        return new GeoPoint(lat, lng);
    }

    public boolean isMajor() {
        return "large".equals(size) || "medium".equals(size);
    }
}
