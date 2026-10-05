package com.wanderly.stay;

import java.util.List;

public final class HotelDtos {

    private HotelDtos() {
    }

    /** A real place to stay (OpenStreetMap) as returned by the hotel search; prices are added per request. */
    public record Listing(String id, String name, String kind, double lat, double lng, String street) {
    }

    /**
     * {@code perNight}/{@code total} are indicative estimates ({@code priceEstimated}), computed per
     * night for the actual dates; {@code matchesBudget} says whether it fits the requested budget.
     */
    public record Hotel(String id, String name, String kind, String tier, double lat, double lng, double distanceKm,
                        String street, int perNight, int total, int nights, int rooms, boolean matchesBudget,
                        boolean priceEstimated, String osmUrl, String mapsUrl) {
    }

    public record Stay(String destination, String checkIn, String checkOut, int nights, int guests, String budget,
                       List<Hotel> hotels, String disclaimer) {
    }
}
