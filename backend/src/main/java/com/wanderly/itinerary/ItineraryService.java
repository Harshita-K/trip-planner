package com.wanderly.itinerary;

import com.wanderly.common.ApiException;
import com.wanderly.common.GeoPoint;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.Topics;
import com.wanderly.messaging.outbox.Outbox;
import com.wanderly.places.Place;
import com.wanderly.places.PlacesService;
import com.wanderly.user.Preferences;
import com.wanderly.user.PreferencesService;
import com.wanderly.user.TravelPace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ItineraryService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryService.class);
    static final int MAX_DAYS = 7;
    private static final double DEFAULT_RADIUS_KM = 15;
    /** Stops are sightseeing; food only appears as the day's lunch suggestion. */
    private static final Set<String> SIGHTSEEING = Set.of(
            "museum", "history", "nature", "religious", "shopping", "landmark", "nightlife", "amusement");

    private final PlacesService places;
    private final PreferencesService preferences;
    private final ItineraryPlanner planner;
    private final ItineraryRepository repository;
    private final Outbox outbox;

    public ItineraryService(PlacesService places, PreferencesService preferences, ItineraryPlanner planner,
                            ItineraryRepository repository, Outbox outbox) {
        this.places = places;
        this.preferences = preferences;
        this.planner = planner;
        this.repository = repository;
        this.outbox = outbox;
    }

    /** Generate and save (logged-in users). */
    @Transactional
    public Itinerary generate(UUID userId, ItineraryDtos.GenerateRequest request) {
        ItineraryPlan plan = plan(userId, request);
        Itinerary saved = repository.save(new Itinerary(userId, request.destination().trim(),
                request.startDate(), request.endDate(), plan));

        // Same transaction as the save (D56): a plan that exists is always counted, and vice versa.
        outbox.enqueue(Topics.USER_ACTIVITY, userId.toString(), ActivityEvent.of(userId, ActivityEvent.ITINERARY_GENERATED,
                "itinerary", saved.getId().toString(), dominantCategory(plan), request.destination().trim()));
        return saved;
    }

    /**
     * Generate without saving. Public, so visitors can try the planner before signing up;
     * {@code userId} is null for anonymous visitors (then only the request's own interests and pace apply).
     * Planning is deterministic, so saving the same request later reproduces this exact plan.
     */
    @Transactional(readOnly = true)
    public ItineraryPlan plan(UUID userId, ItineraryDtos.GenerateRequest request) {
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        if (days < 1) {
            throw ApiException.badRequest("endDate must not be before startDate");
        }
        if (days > MAX_DAYS) {
            throw ApiException.badRequest("Itineraries are limited to " + MAX_DAYS + " days");
        }

        Preferences prefs = preferences.forUser(userId);
        Set<String> interests = request.interests() == null ? prefs.interests()
                : PreferencesService.normalise(request.interests());
        TravelPace pace = request.travelPace() == null ? prefs.pace() : TravelPace.from(request.travelPace());
        double radius = request.radiusKm() == null ? DEFAULT_RADIUS_KM : request.radiusKm();

        GeoPoint centre = new GeoPoint(request.lat(), request.lng());
        GeoPoint start = request.hotelLat() != null && request.hotelLng() != null
                ? new GeoPoint(request.hotelLat(), request.hotelLng())
                : centre;

        List<Place> candidates = places.nearby(centre, radius, SIGHTSEEING, 200);
        if (candidates.isEmpty()) {
            throw ApiException.badRequest("No attractions found within " + radius + " km of " + request.destination());
        }

        return planner.plan(new ItineraryPlanner.Request(start, request.startDate(), (int) days, pace,
                interests, candidates, lunchOptions(centre, radius)));
    }

    /** Restaurants for the lunch suggestion. Optional: without them the plan still has a lunch break. */
    private List<Place> lunchOptions(GeoPoint centre, double radius) {
        try {
            return places.nearby(centre, radius, Set.of("food"), 300);
        } catch (RuntimeException e) {
            log.warn("No lunch suggestions near {}: {}", centre, e.getMessage());
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<Itinerary> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Itinerary get(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Itinerary"));
    }

    private static String dominantCategory(ItineraryPlan plan) {
        Map<String, Long> counts = plan.days().stream()
                .flatMap(d -> d.stops().stream())
                .collect(Collectors.groupingBy(ItineraryPlan.Stop::category, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
