package com.wanderly.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTest {

    @Test
    void haversineMatchesKnownDistance() {
        GeoPoint cubbonPark = new GeoPoint(12.9763, 77.5929);
        GeoPoint lalbagh = new GeoPoint(12.9507, 77.5848);
        assertThat(cubbonPark.distanceKm(lalbagh)).isBetween(2.7, 3.2);
    }

    @Test
    void distanceToSelfIsZeroAndSymmetric() {
        GeoPoint a = new GeoPoint(26.9124, 75.7873);
        GeoPoint b = new GeoPoint(12.9716, 77.5946);
        assertThat(a.distanceKm(a)).isZero();
        assertThat(a.distanceKm(b)).isCloseTo(b.distanceKm(a), within(1e-9));
    }
}
