package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.wanderly.common.GeoPoint;
import com.wanderly.config.WanderlyProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live places for any city from OpenTripMap (free tier, built on OpenStreetMap and Wikidata).
 * Returns category-default hours marked {@code estimated}; {@link OsmHoursEnricher} later swaps in
 * real OpenStreetMap hours where they exist. Places tied to an OSM object get the stable id
 * {@code osm-<type>-<id>}. Enabled only when {@code OPENTRIPMAP_API_KEY} is set.
 */
@Component
@ConditionalOnExpression("!'${wanderly.places.opentripmap.api-key:}'.isBlank()")
public class OpenTripMapPlacesProvider implements PlacesProvider {

    /** Our category -> OpenTripMap "kinds". Ordered: the first match wins when a place has several kinds. */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("museum", "museums");
        KINDS.put("religious", "religion");
        KINDS.put("food", "foods");
        KINDS.put("shopping", "shops");
        KINDS.put("amusement", "amusements");
        KINDS.put("history", "historic,fortifications,architecture");
        KINDS.put("nature", "natural,gardens_and_parks");
        KINDS.put("landmark", "interesting_places");
    }

    private final RestClient http;
    private final String apiKey;

    public OpenTripMapPlacesProvider(RestClient.Builder builder, WanderlyProperties props) {
        this.http = builder.clone().baseUrl(props.places().opentripmap().baseUrl()).build();
        this.apiKey = props.places().opentripmap().apiKey();
    }

    @Override
    public String name() {
        return "otm";
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        Set<String> kinds = new LinkedHashSet<>();
        (categories.isEmpty() ? KINDS.keySet() : categories).forEach(c -> {
            if (KINDS.containsKey(c)) {
                kinds.add(KINDS.get(c));
            }
        });
        if (kinds.isEmpty()) {
            return List.of();
        }
        JsonNode body = http.get()
                .uri(uri -> uri.path("/places/radius")
                        .queryParam("radius", (int) (radiusKm * 1000))
                        .queryParam("lat", center.lat())
                        .queryParam("lon", center.lng())
                        .queryParam("kinds", String.join(",", kinds))
                        .queryParam("rate", "1")          // skip unrated objects (mostly unnamed)
                        .queryParam("format", "json")
                        .queryParam("limit", 150)
                        .queryParam("apikey", apiKey)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.isArray()) {
            return List.of();
        }

        List<Place> places = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (JsonNode node : body) {
            String name = node.path("name").asText("").trim();
            if (name.isBlank() || !seenNames.add(name.toLowerCase(Locale.ROOT))) {
                continue;   // unnamed, or the same place listed twice (e.g. as a node and a building)
            }
            String category = categoryOf(node.path("kinds").asText(""));
            LocalTime[] estimate = CategoryDefaults.hours(category);
            String osm = node.path("osm").asText("");
            String id = osm.matches("(node|way|relation)/\\d+") ? OsmHoursEnricher.placeId(osm) : "otm-" + node.path("xid").asText();
            Place place = new Place(id, name, category,
                    node.path("point").path("lat").asDouble(), node.path("point").path("lon").asDouble(),
                    ratingOf(node.path("rate").asText("1")), CategoryDefaults.visitMinutes(category),
                    estimate[0], estimate[1], null, 0, Set.of(), Place.ESTIMATED);
            places.add(place.withDistanceFrom(center));
        }
        return places;
    }

    static String categoryOf(String kinds) {
        Set<String> placeKinds = Set.of(kinds.split(","));
        for (Map.Entry<String, String> entry : KINDS.entrySet()) {
            for (String kind : entry.getValue().split(",")) {
                if (placeKinds.contains(kind)) {
                    return entry.getKey();
                }
            }
        }
        return "landmark";
    }

    /** OpenTripMap "rate" is 1..3 with an optional "h" (heritage) suffix; map onto 3.65..4.95. */
    static double ratingOf(String rate) {
        int value = rate.isEmpty() || !Character.isDigit(rate.charAt(0)) ? 1 : rate.charAt(0) - '0';
        return Math.min(5.0, 3.0 + value * 0.65);
    }
}
