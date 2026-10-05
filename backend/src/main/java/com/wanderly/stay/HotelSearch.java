package com.wanderly.stay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.wanderly.common.GeoPoint;
import com.wanderly.common.JsonCache;
import com.wanderly.config.WanderlyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real places to stay near a point, from OpenStreetMap via Photon (no API key; the busy Overpass
 * servers are avoided). Three searches run in parallel (hotels, guest houses, hostels) within ~6 km,
 * results are de-duplicated and cached for 7 days per ~1 km grid cell (hotels change slowly).
 */
@Component
public class HotelSearch {

    private static final Logger log = LoggerFactory.getLogger(HotelSearch.class);
    private static final TypeReference<List<HotelDtos.Listing>> LISTINGS = new TypeReference<>() {
    };
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final double BOX_KM = 6;
    /** Photon matches names, so each kind is searched by the words such places are usually called. */
    private static final Map<String, String> QUERIES = Map.of(
            "hotel", "hotel", "guest house", "guest_house", "hostel", "hostel");

    private final RestClient photon;
    private final JsonCache cache;
    private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

    public HotelSearch(RestClient.Builder builder, JsonCache cache, WanderlyProperties props) {
        this.photon = builder.clone()
                .baseUrl(props.places().osm().photonUrl())
                .defaultHeader("User-Agent", props.places().osm().userAgent())
                .build();
        this.cache = cache;
    }

    /** @return listings, or null if Photon couldn't be reached and nothing is cached. */
    public List<HotelDtos.Listing> near(GeoPoint center) {
        String key = String.format(Locale.ROOT, "hotels:%.2f,%.2f", center.lat(), center.lng());
        return cache.get(key, LISTINGS).orElseGet(() -> {
            List<CompletableFuture<List<HotelDtos.Listing>>> calls = QUERIES.keySet().stream()
                    .map(q -> CompletableFuture.supplyAsync(() -> search(q, center), fanOut))
                    .toList();
            Map<String, HotelDtos.Listing> unique = new LinkedHashMap<>();
            boolean anyOk = false;
            for (CompletableFuture<List<HotelDtos.Listing>> call : calls) {
                List<HotelDtos.Listing> found = call.join();
                if (found != null) {
                    anyOk = true;
                    found.forEach(l -> unique.putIfAbsent(l.id(), l));
                }
            }
            if (!anyOk) {
                return null;
            }
            List<HotelDtos.Listing> result = List.copyOf(unique.values());
            cache.put(key, result, CACHE_TTL);
            return result;
        });
    }

    private List<HotelDtos.Listing> search(String query, GeoPoint c) {
        double dLat = BOX_KM / 111.0;
        double dLng = BOX_KM / (111.0 * Math.cos(Math.toRadians(c.lat())));
        String bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", c.lng() - dLng, c.lat() - dLat, c.lng() + dLng, c.lat() + dLat);
        try {
            JsonNode body = photon.get()
                    .uri(uri -> uri.path("/api/")
                            .queryParam("q", query)
                            .queryParam("lat", c.lat())
                            .queryParam("lon", c.lng())
                            .queryParam("bbox", bbox)
                            .queryParam("limit", 40)
                            .queryParam("lang", "en")
                            .queryParam("osm_tag", "tourism:hotel")
                            .queryParam("osm_tag", "tourism:guest_house")
                            .queryParam("osm_tag", "tourism:hostel")
                            .queryParam("osm_tag", "tourism:motel")
                            .build())
                    .retrieve().body(JsonNode.class);
            return parse(body);
        } catch (RestClientException e) {
            log.warn("Photon hotel search '{}' failed: {}", query, e.getMessage());
            return null;
        }
    }

    static List<HotelDtos.Listing> parse(JsonNode body) {
        List<HotelDtos.Listing> listings = new ArrayList<>();
        for (JsonNode f : body == null ? List.<JsonNode>of() : body.path("features")) {
            JsonNode p = f.path("properties");
            String name = p.path("name").asText("").trim();
            if (name.isEmpty() || !"tourism".equals(p.path("osm_key").asText())) {
                continue;
            }
            String type = switch (p.path("osm_type").asText("")) {
                case "N" -> "node";
                case "W" -> "way";
                case "R" -> "relation";
                default -> "";
            };
            String id = type.isEmpty() ? "photon-" + name.hashCode() : "osm-" + type + "-" + p.path("osm_id").asText();
            String street = (p.path("housenumber").asText("") + " " + p.path("street").asText("")).trim();
            listings.add(new HotelDtos.Listing(id, name, p.path("osm_value").asText("hotel"),
                    f.path("geometry").path("coordinates").path(1).asDouble(),
                    f.path("geometry").path("coordinates").path(0).asDouble(), street));
        }
        return listings;
    }
}
