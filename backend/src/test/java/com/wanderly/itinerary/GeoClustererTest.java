package com.wanderly.itinerary;

import com.wanderly.places.Place;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.wanderly.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class GeoClustererTest {

    @Test
    void separatesDistantGroups() {
        List<Place> places = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            places.add(place("north-" + i, "museum", 13.00 + i * 0.003, 77.60, 4.0));
            places.add(place("south-" + i, "museum", 12.80 + i * 0.003, 77.40, 4.0));
        }

        List<List<Place>> clusters = GeoClusterer.kMeans(places, 2);

        assertThat(clusters).hasSize(2);
        for (List<Place> cluster : clusters) {
            assertThat(cluster).hasSize(4);
            String prefix = cluster.get(0).id().split("-")[0];
            assertThat(cluster).allMatch(p -> p.id().startsWith(prefix));
        }
    }

    @Test
    void handlesMoreClustersThanPlaces() {
        List<Place> places = List.of(place("a", "museum", 13.0, 77.6, 4.0));
        assertThat(GeoClusterer.kMeans(places, 3)).hasSize(1);
        assertThat(GeoClusterer.kMeans(List.of(), 3)).isEmpty();
    }
}
