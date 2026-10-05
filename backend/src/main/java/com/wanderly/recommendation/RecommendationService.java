package com.wanderly.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wanderly.catalog.CatalogDtos;
import com.wanderly.catalog.Event;
import com.wanderly.catalog.EventRepository;
import com.wanderly.common.JsonCache;
import com.wanderly.saved.SaveCount;
import com.wanderly.saved.SavedEventRepository;
import com.wanderly.user.Preferences;
import com.wanderly.user.PreferencesService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F10 personalised feed — "compute offline, serve online" (design doc §12).
 *
 * <p>The offline job (Phase 2, Python) writes {@code recs:{userId}} = JSON array of event ids.
 * Serving is a Redis lookup; no model runs on the request path. Until that job exists — and for
 * brand-new users it hasn't scored yet (cold start) — we fall back to a content-based score.
 */
@Service
public class RecommendationService {

    private static final TypeReference<List<UUID>> ID_LIST = new TypeReference<>() {
    };
    private static final Duration HORIZON = Duration.ofDays(60);

    private final JsonCache cache;
    private final EventRepository events;
    private final PreferencesService preferences;
    private final AffinityStore affinity;
    private final SavedEventRepository saves;

    public RecommendationService(JsonCache cache, EventRepository events, PreferencesService preferences,
                                 AffinityStore affinity, SavedEventRepository saves) {
        this.cache = cache;
        this.events = events;
        this.preferences = preferences;
        this.affinity = affinity;
        this.saves = saves;
    }

    @Transactional(readOnly = true)
    public Feed feed(UUID userId, String city, int limit) {
        Optional<List<UUID>> precomputed = cache.get("recs:" + userId, ID_LIST);
        if (precomputed.isPresent() && !precomputed.get().isEmpty()) {
            Map<UUID, Event> byId = events.findAllById(precomputed.get()).stream()
                    .collect(Collectors.toMap(Event::getId, Function.identity()));
            List<FeedItem> items = precomputed.get().stream()
                    .map(byId::get)
                    .filter(e -> e != null && isUpcoming(e) && matchesCity(e, city))
                    .limit(limit)
                    .map(e -> new FeedItem(CatalogDtos.EventResponse.from(e), 1.0, "Picked for you"))
                    .toList();
            return new Feed("precomputed", items);
        }
        return new Feed("fallback", fallback(userId, city, limit));
    }

    List<FeedItem> fallback(UUID userId, String city, int limit) {
        Preferences prefs = preferences.forUser(userId);
        Map<String, Double> aff = affinity.normalised(userId);
        Instant now = Instant.now();
        Instant horizon = now.plus(HORIZON);

        List<Event> candidates = events.findByStartTimeAfterOrderByStartTimeAsc(now).stream()
                .filter(e -> matchesCity(e, city) && e.getStartTime().isBefore(horizon))
                .toList();
        Map<UUID, Long> saveCounts = candidates.isEmpty() ? Map.of()
                : saves.countByEventIds(candidates.stream().map(Event::getId).toList()).stream()
                        .collect(Collectors.toMap(SaveCount::eventId, SaveCount::saves));
        long mostSaved = saveCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);

        return candidates.stream()
                .map(e -> scoreEvent(e, prefs, aff, popularity(saveCounts.getOrDefault(e.getId(), 0L), mostSaved), now))
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Saves relative to the most-saved candidate; needs a few saves before it means anything. */
    static double popularity(long saves, long mostSaved) {
        return saves < 2 || mostSaved == 0 ? 0 : (double) saves / mostSaved;
    }

    /**
     * 0.40 interest match + 0.30 affinity + 0.20 popularity (how often it's saved) + 0.10 soonness.
     */
    static FeedItem scoreEvent(Event e, Preferences prefs, Map<String, Double> aff, double popularity, Instant now) {
        String category = e.getCategory().toLowerCase(java.util.Locale.ROOT);
        boolean interest = prefs.interests().contains(category);
        double affinityScore = aff.getOrDefault(category, 0.0);
        double daysAway = Duration.between(now, e.getStartTime()).toHours() / 24.0;
        double soonness = Math.max(0, 1 - daysAway / HORIZON.toDays());
        double score = 0.40 * (interest ? 1 : 0) + 0.30 * affinityScore + 0.20 * popularity + 0.10 * soonness;

        String reason = interest ? "Matches your interest in " + category
                : affinityScore >= 0.5 ? "Because you've been exploring " + category
                : popularity >= 0.5 ? "Popular with other planners"
                : "Coming up soon";
        return new FeedItem(CatalogDtos.EventResponse.from(e), Math.round(score * 1000) / 1000.0, reason);
    }

    private static boolean isUpcoming(Event e) {
        return e.getStartTime().isAfter(Instant.now());
    }

    private static boolean matchesCity(Event e, String city) {
        return city == null || city.isBlank() || e.getCity().equalsIgnoreCase(city.trim());
    }

    public record Feed(String source, List<FeedItem> items) {
    }

    public record FeedItem(CatalogDtos.EventResponse event, double score, String reason) {
    }
}
