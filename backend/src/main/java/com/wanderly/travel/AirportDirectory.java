package com.wanderly.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Bundled list of the 116 Indian airports with scheduled flights ({@code travel/airports-in.json}). */
@Component
public class AirportDirectory {

    /** Farther than this from an airport, flying isn't offered as an option. */
    static final double MAX_ACCESS_KM = 120;

    private final List<Airport> airports = new ArrayList<>();

    public AirportDirectory(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("travel/airports-in.json").getInputStream()) {
            for (JsonNode a : mapper.readTree(in).path("airports")) {
                airports.add(new Airport(a.path("iata").asText(), a.path("name").asText(), a.path("city").asText(),
                        a.path("lat").asDouble(), a.path("lng").asDouble(), a.path("size").asText()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load travel/airports-in.json", e);
        }
    }

    /** Nearest airport within reach, preferring a major airport unless a small one is much closer. */
    public Optional<Airport> nearest(GeoPoint point) {
        return airports.stream()
                .filter(a -> a.point().distanceKm(point) <= MAX_ACCESS_KM)
                .min(Comparator.comparingDouble(a -> a.point().distanceKm(point) * (a.isMajor() ? 1.0 : 1.8)));
    }

    int size() {
        return airports.size();
    }
}
