package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.wanderly.common.GeoPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live places for any city straight from OpenStreetMap via Overpass: no API key, no account.
 * One query returns the places <em>and</em> their {@code opening_hours}.
 *
 * <p><b>Selection.</b> Each category maps to OSM tags. Places of worship and parks must have a
 * Wikidata/Wikipedia link, otherwise every street shrine and pocket park would flood results.
 * Restaurants and cafés are only queried when food is requested (dense cities have thousands).
 *
 * <p><b>Ranking.</b> OSM has no star ratings, so {@code rating} is a transparent notability score
 * (3.4–4.7) from tags OSM does have: Wikipedia/Wikidata link, heritage status, tourism tagging and
 * how completely the place is described. The UI labels these "Notable" instead of showing stars.
 */
@Component
public class OverpassPlacesProvider implements PlacesProvider {

    private static final Duration BUDGET = Duration.ofSeconds(40);
    private static final int MAX_RESULTS = 400;

    /** Our category -> Overpass filters (each becomes one statement in the union). */
    private static final Map<String, List<String>> FILTERS = new LinkedHashMap<>();

    static {
        FILTERS.put("museum", List.of("[\"tourism\"=\"museum\"]"));
        FILTERS.put("history", List.of("[\"historic\"~\"^(castle|fort|monument|ruins|archaeological_site|palace|city_gate|tomb|memorial)$\"]"));
        FILTERS.put("religious", List.of("[\"amenity\"=\"place_of_worship\"][\"wikidata\"]"));
        FILTERS.put("nature", List.of(
                "[\"leisure\"~\"^(park|garden)$\"][\"wikidata\"]",
                "[\"leisure\"=\"nature_reserve\"]",
                "[\"natural\"~\"^(beach|waterfall|peak)$\"]",
                "[\"tourism\"=\"viewpoint\"]"));
        FILTERS.put("landmark", List.of("[\"tourism\"=\"attraction\"]"));
        FILTERS.put("shopping", List.of("[\"shop\"=\"mall\"]", "[\"amenity\"=\"marketplace\"]"));
        FILTERS.put("amusement", List.of("[\"tourism\"~\"^(theme_park|zoo|aquarium)$\"]", "[\"leisure\"=\"water_park\"]"));
        FILTERS.put("nightlife", List.of("[\"amenity\"~\"^(bar|pub|nightclub)$\"]"));
        FILTERS.put("food", List.of("[\"amenity\"~\"^(restaurant|cafe)$\"]"));
    }

    /** Categories fetched when the caller asks for "everything": sightseeing, not food (see class doc). */
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "museum", "history", "religious", "nature", "landmark", "shopping", "amusement");

    private final OverpassClient overpass;

    public OverpassPlacesProvider(OverpassClient overpass) {
        this.overpass = overpass;
    }

    @Override
    public String name() {
        return "osm";
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        Set<String> wanted = categories.isEmpty() ? DEFAULT_CATEGORIES : categories;
        String query = query(center, radiusKm, wanted);
        if (query == null) {
            return List.of();
        }
        JsonNode body = overpass.query(query, BUDGET)
                .orElseThrow(() -> new ResourceAccessException("OpenStreetMap (Overpass) is unavailable right now"));
        return parse(body, center, wanted);
    }

    /**
     * A bounding-box query (Overpass answers these from its spatial index, far cheaper than
     * {@code around:}); results are trimmed to the exact radius afterwards by distance.
     */
    static String query(GeoPoint center, double radiusKm, Set<String> categories) {
        double dLat = radiusKm / 111.0;
        double dLng = radiusKm / (111.0 * Math.cos(Math.toRadians(center.lat())));
        String bbox = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f",
                center.lat() - dLat, center.lng() - dLng, center.lat() + dLat, center.lng() + dLng);
        StringBuilder q = new StringBuilder("[out:json][timeout:25][bbox:").append(bbox).append("];(");
        int statements = 0;
        for (Map.Entry<String, List<String>> e : FILTERS.entrySet()) {
            if (categories.contains(e.getKey())) {
                for (String filter : e.getValue()) {
                    q.append("nwr").append(filter).append("[\"name\"];");
                    statements++;
                }
            }
        }
        return statements == 0 ? null : q.append(");out center tags ").append(MAX_RESULTS).append(";").toString();
    }

    static List<Place> parse(JsonNode body, GeoPoint center, Set<String> wanted) {
        List<Place> places = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode el : body.path("elements")) {
            JsonNode tags = el.path("tags");
            String name = englishName(tags);
            String category = categoryOf(tags);
            if (name.isBlank() || category == null || !wanted.contains(category)
                    || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            double lat = el.has("lat") ? el.path("lat").asDouble() : el.path("center").path("lat").asDouble();
            double lng = el.has("lon") ? el.path("lon").asDouble() : el.path("center").path("lon").asDouble();
            if (lat == 0 && lng == 0) {
                continue;
            }
            String id = "osm-" + el.path("type").asText() + "-" + el.path("id").asText();
            LocalTime[] estimate = CategoryDefaults.hours(category);
            Place place = new Place(id, name, category, lat, lng, notability(tags), CategoryDefaults.visitMinutes(category),
                    estimate[0], estimate[1], null, 0, Set.of(), Place.ESTIMATED);
            Place withHours = OpeningHours.parse(tags.path("opening_hours").asText(null))
                    .map(w -> place.withHours(w.opens(), w.closes(), w.closedOn(), Place.OSM))
                    .orElse(place);
            places.add(withHours.withDistanceFrom(center));
        }
        return places;
    }

    /** First match wins, so a museum inside a fort is a museum and a temple tagged historic is religious. */
    static String categoryOf(JsonNode tags) {
        String tourism = tags.path("tourism").asText("");
        String amenity = tags.path("amenity").asText("");
        String leisure = tags.path("leisure").asText("");
        if (tourism.equals("museum")) {
            return "museum";
        }
        if (amenity.equals("place_of_worship")) {
            return "religious";
        }
        if (tourism.matches("theme_park|zoo|aquarium") || leisure.equals("water_park")) {
            return "amusement";
        }
        if (tags.hasNonNull("historic")) {
            return "history";
        }
        if (leisure.matches("park|garden|nature_reserve") || tags.hasNonNull("natural") || tourism.equals("viewpoint")) {
            return "nature";
        }
        if (tags.path("shop").asText("").equals("mall") || amenity.equals("marketplace")) {
            return "shopping";
        }
        if (amenity.matches("bar|pub|nightclub")) {
            return "nightlife";
        }
        if (amenity.matches("restaurant|cafe")) {
            return "food";
        }
        if (tourism.equals("attraction")) {
            return "landmark";
        }
        return null;
    }

    /**
     * Rating stand-in: Wikipedia page views when the bundle has them, otherwise from OSM tags: 3.4 base, +0.7 Wikipedia/Wikidata link
     * (the strongest notability signal), +0.3 heritage listing, +0.1 tourism-tagged, +0.1 opening
     * hours, +0.1 website. Range 3.4–4.7, so it never claims more than "well known".
     */
    static double notability(JsonNode tags) {
        // Bundled data carries real popularity: 30-day Wikipedia page views (added by tools/fetch_osm_places.py).
        // Use the same scale as WikipediaPlacesProvider so bundled and live places rank consistently.
        if (tags.hasNonNull("wanderly:views30")) {
            return WikipediaPlacesProvider.popularity(tags.path("wanderly:views30").asLong(0));
        }
        double score = 3.4;
        if (tags.hasNonNull("wikidata") || tags.hasNonNull("wikipedia")) {
            score += 0.7;
        }
        if (tags.hasNonNull("heritage")) {
            score += 0.3;
        }
        if (tags.hasNonNull("tourism")) {
            score += 0.1;
        }
        if (tags.hasNonNull("opening_hours")) {
            score += 0.1;
        }
        if (tags.hasNonNull("website") || tags.hasNonNull("contact:website")) {
            score += 0.1;
        }
        return Math.round(score * 10) / 10.0;
    }

    /** Many Indian places are named in the local script; prefer the English name when tagged. */
    static String englishName(JsonNode tags) {
        for (String key : List.of("name:en", "int_name", "name")) {
            String value = tags.path(key).asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
