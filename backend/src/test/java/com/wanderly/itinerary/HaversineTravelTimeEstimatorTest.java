package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaversineTravelTimeEstimatorTest {

    private final HaversineTravelTimeEstimator estimator = new HaversineTravelTimeEstimator();

    @Test
    void samePlaceTakesNoTime() {
        GeoPoint p = new GeoPoint(12.97, 77.59);
        assertThat(estimator.minutes(p, p)).isZero();
    }

    @Test
    void growsWithDistance() {
        GeoPoint origin = new GeoPoint(12.97, 77.59);
        int near = estimator.minutes(origin, new GeoPoint(12.98, 77.59));   // ~1.1 km
        int far = estimator.minutes(origin, new GeoPoint(13.06, 77.59));    // ~10 km
        assertThat(near).isGreaterThanOrEqualTo(HaversineTravelTimeEstimator.OVERHEAD_MIN);
        assertThat(far).isGreaterThan(near);
        // 10 km * 1.4 / 22 km/h = ~38 min driving + 5 min overhead
        assertThat(far).isBetween(40, 48);
    }
}
