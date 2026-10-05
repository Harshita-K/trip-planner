package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OpenStreetMap places for the ~85 bundled destinations, fetched ahead of time by
 * {@code tools/fetch_osm_places.py} ({@code places/osm-cities.json}). Public Overpass servers are
 * frequently overloaded, so popular cities must not depend on them at request time: these are
 * served from memory, instantly and reliably. Raw OSM tags are stored and interpreted by
 * {@link OverpassPlacesProvider#parse}, so bundled and live places are mapped by the same code.
 */
@Component
public class BundledOsmPlaces implements PlacesProvider {

    /** A point within this distance of a bundled city's centre is served from the bundle. */
    static final double COVERAGE_KM = 15;
    private static final Set<String> ALL_CATEGORIES = Set.of("museum", "history", "religious", "nature", "landmark",
            "shopping", "amusement", "nightlife", "food");
    private static final Logger log = LoggerFactory.getLogger(BundledOsmPlaces.class);

    private record CityPlaces(String city, GeoPoint centre, List<Place> places) {
    }

    private final List<CityPlaces> cities = new ArrayList<>();

    public BundledOsmPlaces(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource("places/osm-cities.json");
        if (!resource.exists()) {
            log.info("No bundled OSM places (run tools/fetch_osm_places.py); non-curated cities use live Overpass");
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            for (JsonNode city : mapper.readTree(in).path("cities")) {
                GeoPoint centre = new GeoPoint(city.path("lat").asDouble(), city.path("lng").asDouble());
                String name = city.path("name").asText();
                List<Place> places = OverpassPlacesProvider.parse(city, centre, ALL_CATEGORIES).stream()
                        .map(p -> p.withCity(name))
                        .toList();
                cities.add(new CityPlaces(name, centre, places));
            }
            log.info("Loaded bundled OSM places for {} cities ({} places)", cities.size(),
                    cities.stream().mapToInt(c -> c.places().size()).sum());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read places/osm-cities.json", e);
        }
    }

    @Override
    public String name() {
        return "osm-bundled";
    }

    public boolean covers(GeoPoint point) {
        return cities.stream().anyMatch(c -> c.centre().distanceKm(point) <= COVERAGE_KM);
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        return cities.stream()
                .filter(c -> c.centre().distanceKm(center) <= COVERAGE_KM + radiusKm)
                .flatMap(c -> c.places().stream())
                .filter(p -> categories.isEmpty() || categories.contains(p.category()))
                .map(p -> p.withDistanceFrom(center))
                .filter(p -> p.distanceKm() <= radiusKm)
                .toList();
    }

    public Set<String> cityNames() {
        Set<String> names = new TreeSet<>();
        cities.forEach(c -> names.add(c.city()));
        return names;
    }
}
