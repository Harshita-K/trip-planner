package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;

/**
 * Minutes to get from A to B. The haversine implementation is the design doc's fallback; an
 * OpenRouteService/OSRM implementation can replace it without touching the planner.
 */
public interface TravelTimeEstimator {

    int minutes(GeoPoint from, GeoPoint to);
}
