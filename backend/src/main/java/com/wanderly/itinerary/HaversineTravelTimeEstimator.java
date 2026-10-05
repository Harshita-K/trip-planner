package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import org.springframework.stereotype.Component;

/**
 * Straight-line distance x a road-winding factor at average city speed, plus a fixed overhead
 * for parking/walking/finding the entrance. Crude but monotonic in distance, which is what the
 * planner needs; swap for a routing API when accuracy matters.
 */
@Component
public class HaversineTravelTimeEstimator implements TravelTimeEstimator {

    static final double ROAD_FACTOR = 1.4;
    static final double AVG_SPEED_KMH = 22.0;
    static final int OVERHEAD_MIN = 5;

    @Override
    public int minutes(GeoPoint from, GeoPoint to) {
        double km = from.distanceKm(to);
        if (km < 0.05) {
            return 0;
        }
        return (int) Math.ceil(km * ROAD_FACTOR / AVG_SPEED_KMH * 60) + OVERHEAD_MIN;
    }
}
