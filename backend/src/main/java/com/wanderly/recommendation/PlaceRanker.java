package com.wanderly.recommendation;

import com.wanderly.places.Place;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Content-based re-ranking of candidate places for one user (design doc §10, F5/F7).
 *
 * <pre>
 * score = 0.45 * rating/5            quality
 *       + 0.30 * interestMatch       declared taste (preferences)
 *       + 0.15 * affinity[category]  observed taste (recent activity from Kafka, 0..1)
 *       + 0.10 * proximity           1 at the origin, 0 at the search radius
 * </pre>
 *
 * Anonymous users get the rating + proximity terms only. Pure function: no I/O, unit-tested.
 */
public final class PlaceRanker {

    static final double W_RATING = 0.45;
    static final double W_INTEREST = 0.30;
    static final double W_AFFINITY = 0.15;
    static final double W_PROXIMITY = 0.10;

    private PlaceRanker() {
    }

    public static List<RankedPlace> rank(List<Place> candidates, Set<String> interests,
                                         Map<String, Double> affinity, double radiusKm) {
        return candidates.stream()
                .map(p -> score(p, interests, affinity, radiusKm))
                .sorted(Comparator.comparingDouble(RankedPlace::score).reversed())
                .toList();
    }

    /** Live places (OpenStreetMap / OpenTripMap / Wikipedia) carry a notability or popularity score, not review ratings. */
    private static boolean isLive(Place p) {
        return p.id().startsWith("osm-") || p.id().startsWith("otm-") || p.id().startsWith("wiki-");
    }

    static RankedPlace score(Place p, Set<String> interests, Map<String, Double> affinity, double radiusKm) {
        boolean interestMatch = interests.contains(p.category());
        double affinityScore = affinity.getOrDefault(p.category(), 0.0);
        double proximity = radiusKm <= 0 ? 0 : Math.max(0, 1 - p.distanceKm() / radiusKm);
        double score = W_RATING * (p.rating() / 5.0)
                + W_INTEREST * (interestMatch ? 1 : 0)
                + W_AFFINITY * affinityScore
                + W_PROXIMITY * proximity;

        String reason;
        if (interestMatch) {
            reason = "Matches your interest in " + p.category();
        } else if (affinityScore >= 0.5) {
            reason = "Because you've been exploring " + p.category();
        } else if (isLive(p) && p.rating() >= 4.1) {
            reason = "Well-known spot";          // live ratings are a notability score, not reviews
        } else if (!isLive(p) && p.rating() >= 4.5) {
            reason = "Highly rated";
        } else {
            reason = "Nearby";
        }
        return new RankedPlace(p, Math.round(score * 1000) / 1000.0, reason);
    }
}
