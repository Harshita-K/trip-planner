package com.wanderly.places;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Curated dataset (Bengaluru, Jaipur) with hand-checked opening hours and visit durations. Always
 * preferred inside the cities it covers; it also keeps tests deterministic and the app usable with
 * no API keys.
 */
@Component
public class SeedPlacesProvider implements PlacesProvider {

    /** A point within this distance of any curated place counts as a curated city. */
    static final double COVERAGE_KM = 25;

    private final List<Place> places;

    public SeedPlacesProvider(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("places/seed-places.json").getInputStream()) {
            this.places = List.copyOf(mapper.readValue(in, new TypeReference<List<Place>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load seed places", e);
        }
    }

    @Override
    public String name() {
        return "seed";
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        return places.stream()
                .filter(p -> categories.isEmpty() || categories.contains(p.category()))
                .map(p -> p.withDistanceFrom(center))
                .filter(p -> p.distanceKm() <= radiusKm)
                .toList();
    }

    public boolean covers(GeoPoint point) {
        return places.stream().anyMatch(p -> point.distanceKm(p.point()) <= COVERAGE_KM);
    }

    public Set<String> cities() {
        Set<String> cities = new TreeSet<>();
        places.stream().map(Place::city).filter(Objects::nonNull).forEach(cities::add);
        return cities;
    }
}
