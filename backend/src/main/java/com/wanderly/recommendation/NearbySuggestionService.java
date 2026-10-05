package com.wanderly.recommendation;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.PlacesService;
import com.wanderly.user.Preferences;
import com.wanderly.user.PreferencesService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** F7: personalised food + attractions around a point (typically an event venue). */
@Service
public class NearbySuggestionService {

    private static final double RADIUS_KM = 3.0;
    private static final int TOP_N = 5;
    private static final Set<String> ATTRACTIONS = Set.of(
            "museum", "history", "nature", "religious", "shopping", "landmark", "nightlife", "amusement");

    private final PlacesService places;
    private final PreferencesService preferences;
    private final AffinityStore affinity;

    public NearbySuggestionService(PlacesService places, PreferencesService preferences, AffinityStore affinity) {
        this.places = places;
        this.preferences = preferences;
        this.affinity = affinity;
    }

    public NearbySuggestions around(UUID userId, double lat, double lng) {
        GeoPoint origin = new GeoPoint(lat, lng);
        Preferences prefs = preferences.forUser(userId);
        Map<String, Double> aff = affinity.normalised(userId);

        List<RankedPlace> food = PlaceRanker.rank(places.nearby(origin, RADIUS_KM, Set.of("food"), 50),
                prefs.interests(), aff, RADIUS_KM).stream().limit(TOP_N).toList();
        List<RankedPlace> attractions = PlaceRanker.rank(places.nearby(origin, RADIUS_KM, ATTRACTIONS, 50),
                prefs.interests(), aff, RADIUS_KM).stream().limit(TOP_N).toList();
        return new NearbySuggestions(food, attractions);
    }

    public record NearbySuggestions(List<RankedPlace> food, List<RankedPlace> attractions) {
    }
}
