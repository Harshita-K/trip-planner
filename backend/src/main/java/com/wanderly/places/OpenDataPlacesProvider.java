package com.wanderly.places;

import com.wanderly.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live places for cities outside the curated and bundled sets, from open data with no API key:
 * sights from Wikipedia (fast, reliable, ranked by page views), food and nightlife from OpenStreetMap
 * via Overpass. If Overpass is busy the sights still come back; only if nothing at all can be
 * fetched does the caller get "unavailable".
 */
@Component
public class OpenDataPlacesProvider implements PlacesProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenDataPlacesProvider.class);
    private static final Set<String> FROM_OSM = Set.of("food", "nightlife");
    private static final Set<String> SIGHTSEEING = Set.of("museum", "history", "religious", "nature", "landmark",
            "shopping", "amusement");

    private final WikipediaPlacesProvider wikipedia;
    private final OverpassPlacesProvider overpass;

    public OpenDataPlacesProvider(WikipediaPlacesProvider wikipedia, OverpassPlacesProvider overpass) {
        this.wikipedia = wikipedia;
        this.overpass = overpass;
    }

    @Override
    public String name() {
        return "open";
    }

    /** {@code complete} is false if one source failed, so the caller won't cache a partial list. */
    public record Fetch(List<Place> places, boolean complete) {
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        return fetch(center, radiusKm, categories).places();
    }

    public Fetch fetch(GeoPoint center, double radiusKm, Set<String> categories) {
        Set<String> sights = new HashSet<>(categories.isEmpty() ? SIGHTSEEING : categories);
        Set<String> osm = new HashSet<>(sights);
        sights.removeAll(FROM_OSM);
        osm.retainAll(FROM_OSM);

        List<Place> places = new ArrayList<>();
        int failures = 0;
        if (!sights.isEmpty()) {
            try {
                places.addAll(wikipedia.nearby(center, radiusKm, sights));
            } catch (RestClientException e) {
                failures++;
                log.warn("Wikipedia lookup failed: {}", e.getMessage());
            }
        }
        if (!osm.isEmpty()) {
            try {
                places.addAll(overpass.nearby(center, radiusKm, osm));
            } catch (RestClientException e) {
                failures++;
                log.warn("Overpass food lookup failed: {}", e.getMessage());
            }
        }
        int sources = (sights.isEmpty() ? 0 : 1) + (osm.isEmpty() ? 0 : 1);
        if (failures > 0 && failures == sources) {
            throw new ResourceAccessException("Live place data is unavailable right now");
        }
        return new Fetch(places, failures == 0);
    }
}
