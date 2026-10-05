package com.wanderly.places;

import com.wanderly.common.GeoPoint;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.recommendation.AffinityStore;
import com.wanderly.recommendation.PlaceRanker;
import com.wanderly.recommendation.RankedPlace;
import com.wanderly.user.CurrentUser;
import com.wanderly.user.Preferences;
import com.wanderly.user.PreferencesService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private final PlacesService places;
    private final PreferencesService preferences;
    private final AffinityStore affinity;
    private final EventPublisher publisher;

    public PlacesController(PlacesService places, PreferencesService preferences, AffinityStore affinity,
                            EventPublisher publisher) {
        this.places = places;
        this.preferences = preferences;
        this.affinity = affinity;
        this.publisher = publisher;
    }

    /** Whether live data is available beyond the curated cities (the UI greys out cities it can't serve). */
    @GetMapping("/coverage")
    public PlacesService.Coverage coverage() {
        return places.coverage();
    }

    /**
     * F5: tourist spots near a location, re-ranked for the caller when a token is present.
     * {@code category} accepts a comma-separated list, e.g. {@code museum,history}.
     */
    @GetMapping("/nearby")
    public List<RankedPlace> nearby(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                    @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                                    @RequestParam(defaultValue = "5") @DecimalMin("0.5") @DecimalMax("50") double radiusKm,
                                    @RequestParam(required = false) Set<String> category,
                                    @RequestParam(defaultValue = "20") int limit) {
        UUID userId = CurrentUser.idOrNull(jwt);
        Set<String> categories = category == null ? Set.of() : PreferencesService.normalise(category);
        List<Place> candidates = places.nearby(new GeoPoint(lat, lng), radiusKm, categories, 200);

        Preferences prefs = preferences.forUser(userId);
        List<RankedPlace> ranked = PlaceRanker.rank(candidates, prefs.interests(), affinity.normalised(userId), radiusKm)
                .stream().limit(Math.clamp(limit, 1, 100)).toList();

        if (userId != null && categories.size() == 1) {
            String only = categories.iterator().next();
            publisher.activity(userId, ActivityEvent.PLACES_SEARCHED, "place", null, only, null);
        }
        return ranked;
    }
}
