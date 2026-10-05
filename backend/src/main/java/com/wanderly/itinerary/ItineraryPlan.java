package com.wanderly.itinerary;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.List;

/**
 * The generated plan; stored as-is in {@code itineraries.plan} (JSONB). Shape per design doc §11,
 * plus a lunch break per day. Plans saved before lunch existed simply have no {@code lunch}.
 */
public record ItineraryPlan(
        String algorithm,
        List<Day> days,
        List<String> unscheduled,
        int totalTravelMinutes) implements Serializable {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(String date, List<Stop> stops, int travelMinutes, Lunch lunch) implements Serializable {
    }

    /** {@code waitMin}: minutes spent waiting outside because the stop hadn't opened yet (absent if none). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stop(String placeId, String name, String category, double lat, double lng,
                       String arrive, String leave, Integer travelToNextMin, Integer waitMin) implements Serializable {
    }

    /**
     * The day's lunch break, taken after the first {@code afterStops} stops. {@code placeId}/{@code name}
     * suggest a well-rated food place near where you are at that time; absent if none is known.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lunch(String start, String end, int afterStops, String placeId, String name,
                        Double lat, Double lng, Double distanceKm) implements Serializable {
    }
}
