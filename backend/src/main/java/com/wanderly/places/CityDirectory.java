package com.wanderly.places;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import com.wanderly.common.JsonCache;
import com.wanderly.config.WanderlyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * City search for India, in two tiers that the UI shows together:
 *
 * <ul>
 *   <li>{@link #search}: instant, no network. Filters a bundled list of ~85 destinations
 *       ({@code cities/india.json}), including common old names (Bangalore, Bombay, Pondicherry…).</li>
 *   <li>{@link #suggest}: any city, town or village via Photon, an OpenStreetMap geocoder that
 *       (unlike Nominatim) permits search-as-you-type and needs no API key. Results are restricted
 *       to India, ranked city > town > village, and cached for 30 days. Photon is a shared fair-use
 *       service, so calls are capped at 2 concurrent and any failure just returns no suggestions:
 *       the instant tier still works.</li>
 * </ul>
 */
@Component
public class CityDirectory {

    public record City(String name, String region, double lat, double lng, boolean curated, String source) {
    }

    private record Entry(String name, String region, double lat, double lng) {
    }

    private static final Logger log = LoggerFactory.getLogger(CityDirectory.class);
    private static final TypeReference<List<City>> CITY_LIST = new TypeReference<>() {
    };
    private static final Duration SUGGEST_CACHE = Duration.ofDays(30);
    private static final TypeReference<double[]> POINT = new TypeReference<>() {
    };
    /** A venue further than this from its city's centre is probably a different place with the same name. */
    private static final double VENUE_MAX_KM = 40;
    /** India's bounding box (lon/lat), used to bias and limit Photon. */
    private static final String INDIA_BBOX = "68.1,6.5,97.5,35.7";
    private static final List<String> PLACE_TYPES = List.of("city", "town", "village");
    private static final List<String> POPULAR = List.of("Bengaluru", "Jaipur", "Goa", "Mumbai", "Delhi", "Udaipur",
            "Agra", "Varanasi", "Kochi", "Rishikesh", "Hyderabad", "Darjeeling");
    /** Old or common alternative names -> bundled city name. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("bangalore", "Bengaluru"), Map.entry("bombay", "Mumbai"), Map.entry("calcutta", "Kolkata"),
            Map.entry("madras", "Chennai"), Map.entry("pondicherry", "Puducherry"), Map.entry("pondy", "Puducherry"),
            Map.entry("ooty", "Ooty"), Map.entry("udhagamandalam", "Ooty"), Map.entry("cochin", "Kochi"),
            Map.entry("trivandrum", "Thiruvananthapuram"), Map.entry("mysore", "Mysuru"),
            Map.entry("mangalore", "Mangaluru"), Map.entry("benares", "Varanasi"), Map.entry("banaras", "Varanasi"),
            Map.entry("allahabad", "Prayagraj"), Map.entry("coorg", "Madikeri"), Map.entry("vizag", "Visakhapatnam"),
            Map.entry("panjim", "Goa"), Map.entry("panaji", "Goa"), Map.entry("new delhi", "Delhi"),
            Map.entry("alleppey", "Alappuzha"), Map.entry("baroda", "Vadodara"));

    private final List<City> cities;
    private final PlacesService places;
    private final JsonCache cache;
    private final RestClient photon;
    private final Semaphore photonSlots = new Semaphore(2);

    public CityDirectory(ObjectMapper mapper, PlacesService places, JsonCache cache, RestClient.Builder builder,
                         WanderlyProperties props) {
        this.places = places;
        this.cache = cache;
        try (InputStream in = new ClassPathResource("cities/india.json").getInputStream()) {
            this.cities = mapper.readValue(in, new TypeReference<List<Entry>>() {
                    }).stream()
                    .map(e -> new City(e.name(), e.region(), e.lat(), e.lng(),
                            places.isCurated(new GeoPoint(e.lat(), e.lng())), "bundled"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load cities/india.json", e);
        }
        this.photon = builder.clone()   // timeouts come from HttpClientConfig
                .baseUrl(props.places().osm().photonUrl())
                .defaultHeader("User-Agent", props.places().osm().userAgent())
                .build();
    }

    /** Instant search: popular cities for an empty query, else prefix, alias, then substring/state matches. */
    public List<City> search(String query, int limit) {
        String q = normalise(query);
        if (q.isEmpty()) {
            return POPULAR.stream().flatMap(name -> byName(name).stream()).limit(limit).toList();
        }
        // Ranking: name starts with the query > an old/alternative name does > substring or state match.
        Map<String, City> ordered = new LinkedHashMap<>();
        List<City> contains = new ArrayList<>();
        for (City c : cities) {
            String name = normalise(c.name());
            if (name.startsWith(q)) {
                ordered.putIfAbsent(c.name(), c);
            } else if (name.contains(q) || normalise(c.region()).startsWith(q)) {
                contains.add(c);
            }
        }
        ALIASES.forEach((alias, name) -> {
            if (alias.startsWith(q)) {
                byName(name).forEach(c -> ordered.putIfAbsent(c.name(), c));
            }
        });
        contains.forEach(c -> ordered.putIfAbsent(c.name(), c));
        return ordered.values().stream().limit(limit).toList();
    }

    /** Search-as-you-type over every city, town and village in India (Photon). Best effort: [] on failure. */
    public List<City> suggest(String query) {
        String q = normalise(query);
        if (q.length() < 2 || q.length() > 80) {
            return List.of();
        }
        String key = "photon:in:" + q;
        return cache.get(key, CITY_LIST).orElseGet(() -> {
            List<City> found = callPhoton(q);
            if (found != null) {
                cache.put(key, found, SUGGEST_CACHE);   // failures (null) are not cached
            }
            return found == null ? List.of() : found;
        });
    }

    /**
     * Best-effort geocoding of a venue ("Diggi Palace") near a city's centre, for user-listed events.
     * Empty if Photon is unavailable or its best match is too far from the city. Cached for 30 days.
     */
    public Optional<GeoPoint> locate(String venue, String city, GeoPoint near) {
        String q = normalise(venue + ", " + city);
        if (q.length() > 200) {
            return Optional.empty();
        }
        String key = "photon:venue:%.2f,%.2f:%s".formatted(near.lat(), near.lng(), q);
        Optional<double[]> cached = cache.get(key, POINT);
        if (cached.isPresent()) {
            return cached.get().length == 2 ? Optional.of(new GeoPoint(cached.get()[0], cached.get()[1])) : Optional.empty();
        }
        Optional<GeoPoint> found = callPhotonNear(q, near);
        if (found != null) {
            cache.put(key, found.map(p -> new double[]{p.lat(), p.lng()}).orElse(new double[0]), SUGGEST_CACHE);
        }
        return found == null ? Optional.empty() : found;
    }

    /** Null on failure (not cached), empty if nothing plausible was found. */
    private Optional<GeoPoint> callPhotonNear(String q, GeoPoint near) {
        try {
            if (!photonSlots.tryAcquire(2, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            JsonNode body = photon.get()
                    .uri(uri -> uri.path("/api/")
                            .queryParam("q", q)
                            .queryParam("limit", 1)
                            .queryParam("lang", "en")
                            .queryParam("lat", near.lat())
                            .queryParam("lon", near.lng())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode f : body == null ? List.<JsonNode>of() : body.path("features")) {
                GeoPoint p = new GeoPoint(f.path("geometry").path("coordinates").path(1).asDouble(),
                        f.path("geometry").path("coordinates").path(0).asDouble());
                if (near.distanceKm(p) <= VENUE_MAX_KM) {
                    return Optional.of(new GeoPoint(round4(p.lat()), round4(p.lng())));
                }
            }
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Photon venue lookup failed for '{}': {}", q, e.getMessage());
            return null;
        } finally {
            photonSlots.release();
        }
    }

    private List<City> callPhoton(String q) {
        try {
            if (!photonSlots.tryAcquire(2, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            JsonNode body = photon.get()
                    .uri(uri -> uri.path("/api/")
                            .queryParam("q", q)
                            .queryParam("limit", 10)
                            .queryParam("lang", "en")
                            .queryParam("bbox", INDIA_BBOX)
                            .queryParam("osm_tag", "place:city")
                            .queryParam("osm_tag", "place:town")
                            .queryParam("osm_tag", "place:village")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            record Hit(City city, int rank) {
            }
            Map<String, Hit> unique = new LinkedHashMap<>();
            for (JsonNode f : body == null ? List.<JsonNode>of() : body.path("features")) {
                JsonNode p = f.path("properties");
                String name = p.path("name").asText("");
                String type = p.path("osm_value").asText("");
                if (name.isBlank() || !"IN".equalsIgnoreCase(p.path("countrycode").asText()) || !PLACE_TYPES.contains(type)) {
                    continue;   // the bbox also covers neighbouring countries
                }
                double lng = f.path("geometry").path("coordinates").path(0).asDouble();
                double lat = f.path("geometry").path("coordinates").path(1).asDouble();
                String region = p.path("state").asText("");
                City city = new City(name, region, round4(lat), round4(lng), places.isCurated(new GeoPoint(lat, lng)), "photon");
                unique.putIfAbsent(name + "|" + region, new Hit(city, PLACE_TYPES.indexOf(type)));
            }
            // Cities before towns before villages; Photon's own relevance order within each.
            return unique.values().stream()
                    .sorted(Comparator.comparingInt(Hit::rank))
                    .map(Hit::city)
                    .limit(6)
                    .toList();
        } catch (RestClientException e) {
            log.warn("Photon lookup failed for '{}': {}", q, e.getMessage());
            return null;
        } finally {
            photonSlots.release();
        }
    }

    private List<City> byName(String name) {
        return cities.stream().filter(c -> c.name().equals(name)).toList();
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
