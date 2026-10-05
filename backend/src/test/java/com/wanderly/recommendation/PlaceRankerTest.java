package com.wanderly.recommendation;

import com.wanderly.places.Place;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wanderly.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceRankerTest {

    private static Place at(Place p, double distanceKm) {
        return new Place(p.id(), p.name(), p.category(), p.lat(), p.lng(), p.rating(), p.visitMinutes(),
                p.opens(), p.closes(), p.city(), distanceKm);
    }

    @Test
    void declaredInterestBeatsSlightlyHigherRating() {
        Place museum = at(place("museum", "museum", 0, 0, 4.0), 1.0);
        Place park = at(place("park", "nature", 0, 0, 4.8), 1.0);

        List<RankedPlace> ranked = PlaceRanker.rank(List.of(park, museum), Set.of("museum"), Map.of(), 5);

        assertThat(ranked).extracting(r -> r.place().id()).containsExactly("museum", "park");
        assertThat(ranked.get(0).reason()).contains("interest");
    }

    @Test
    void observedAffinityBreaksTies() {
        Place food = at(place("food", "food", 0, 0, 4.2), 1.0);
        Place shop = at(place("shop", "shopping", 0, 0, 4.2), 1.0);

        List<RankedPlace> ranked = PlaceRanker.rank(List.of(shop, food), Set.of(), Map.of("food", 1.0), 5);

        assertThat(ranked.get(0).place().id()).isEqualTo("food");
        assertThat(ranked.get(0).reason()).contains("exploring");
    }

    @Test
    void anonymousUsersGetRatingAndProximity() {
        Place close = at(place("close", "museum", 0, 0, 4.3), 0.2);
        Place far = at(place("far", "museum", 0, 0, 4.3), 4.8);

        List<RankedPlace> ranked = PlaceRanker.rank(List.of(far, close), Set.of(), Map.of(), 5);

        assertThat(ranked).extracting(r -> r.place().id()).containsExactly("close", "far");
    }
}
