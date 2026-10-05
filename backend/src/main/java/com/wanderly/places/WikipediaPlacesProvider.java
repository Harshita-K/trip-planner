package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wanderly.common.GeoPoint;
import com.wanderly.config.WanderlyProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Famous sights near a point from Wikipedia: no API key, fast (~1 s) and reliable, unlike public
 * Overpass servers. One query ({@code generator=geosearch}) returns each article's coordinates,
 * short description, Wikidata id and 30 days of page views (page views arrive over a few
 * {@code continue} rounds, which are followed and merged).
 *
 * <ul>
 *   <li><b>Filtering:</b> geosearch returns every article with coordinates (the city itself,
 *       constituencies, stations, colleges…). The short description plus title is classified into
 *       our categories by keyword; anything matching an exclusion or nothing at all is dropped.</li>
 *   <li><b>Ranking:</b> page views are a real popularity signal. 30-day views map onto a 3.2–4.8 score
 *       (log scale: ~100 → 3.8, ~2k → 4.3, ~100k+ → 4.8).</li>
 *   <li><b>Gaps:</b> no opening hours (added from OpenStreetMap via Wikidata ids, see
 *       {@link OsmHoursEnricher}) and no restaurants (Overpass covers those). Geosearch is capped
 *       at a 10 km radius.</li>
 * </ul>
 */
@Component
public class WikipediaPlacesProvider implements PlacesProvider {

    private static final int MAX_RADIUS_M = 10_000;

    /** Articles that have coordinates but aren't somewhere you'd visit. */
    private static final Pattern EXCLUDE = Pattern.compile("\\b(city|town|village|district|constituency|tehsil|taluk|"
            + "railway station|metro station|bus station|airport|college|university|school|institute|hospital|company|"
            + "neighbourhood|neighborhood|suburb|locality|ward|road|street|highway|diocese|kingdom|dynasty|"
            + "stadium|power station|mine|census town|municipality|state assembly|legislative)\\b");

    /** Ordered: first match wins (a temple inside a fort is religious; a palace museum is a museum). */
    private static final Map<String, Pattern> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("museum", Pattern.compile("\\b(museum|gallery|art centre|art center)\\b"));
        CATEGORIES.put("religious", Pattern.compile("\\b(temple|mosque|masjid|church|cathedral|basilica|gurudwara|gurdwara|"
                + "dargah|shrine|monastery|gompa|synagogue|mandir|jain)\\b"));
        CATEGORIES.put("amusement", Pattern.compile("\\b(zoo|aquarium|theme park|amusement park|water park)\\b"));
        CATEGORIES.put("history", Pattern.compile("\\b(fort|fortress|palace|haveli|tomb|monument|ruins|archaeological|stepwell|"
                + "baori|memorial|gate|mahal|cenotaph|chhatri|minar|stambha|tower|heritage|citadel|caves?)\\b"));
        CATEGORIES.put("nature", Pattern.compile("\\b(lake|garden|park|beach|waterfalls?|falls|hill|peak|sanctuary|"
                + "national park|reserve|island|ghat|valley|forest)\\b"));
        CATEGORIES.put("shopping", Pattern.compile("\\b(market|bazaar|mall)\\b"));
        CATEGORIES.put("landmark", Pattern.compile("\\b(square|bridge|clock tower|observatory|planetarium|landmark)\\b"));
    }

    private static final Set<String> SIGHTSEEING = Set.of("museum", "history", "religious", "nature", "landmark",
            "shopping", "amusement");

    private final RestClient wikipedia;

    public WikipediaPlacesProvider(RestClient.Builder builder, WanderlyProperties props) {
        this.wikipedia = builder.clone()
                .baseUrl(props.places().osm().wikipediaUrl())
                .defaultHeader("User-Agent", props.places().osm().userAgent())
                .build();
    }

    @Override
    public String name() {
        return "wiki";
    }

    @Override
    public List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories) {
        int radius = (int) Math.min(MAX_RADIUS_M, radiusKm * 1000);
        // Wikipedia pages extra data (page views especially) across "continue" rounds; merge them by page id.
        Map<Long, ObjectNode> pages = new LinkedHashMap<>();
        Map<String, String> cont = Map.of();
        for (int round = 0; round < 5; round++) {
            Map<String, String> c = cont;
            JsonNode body = wikipedia.get()
                    .uri(uri -> {
                        uri.queryParam("action", "query")
                                .queryParam("format", "json")
                                .queryParam("formatversion", "2")
                                .queryParam("generator", "geosearch")
                                .queryParam("ggscoord", String.format(Locale.ROOT, "%.5f|%.5f", center.lat(), center.lng()))
                                .queryParam("ggsradius", radius)
                                .queryParam("ggslimit", 50)
                                .queryParam("prop", "coordinates|pageprops|description|pageviews")
                                .queryParam("ppprop", "wikibase_item")
                                .queryParam("pvipdays", 30)
                                .queryParam("colimit", "max");
                        c.forEach(uri::queryParam);
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                break;
            }
            for (JsonNode page : body.path("query").path("pages")) {
                ObjectNode merged = pages.computeIfAbsent(page.path("pageid").asLong(), k -> JsonNodeFactory.instance.objectNode());
                page.fields().forEachRemaining(f -> {
                    if (f.getKey().equals("pageviews") && f.getValue().isObject()) {
                        ObjectNode views = merged.has("pageviews") ? (ObjectNode) merged.get("pageviews") : merged.putObject("pageviews");
                        views.setAll((ObjectNode) f.getValue());
                    } else if (!f.getValue().isNull()) {
                        merged.set(f.getKey(), f.getValue());
                    }
                });
            }
            if (!body.has("continue")) {
                break;
            }
            Map<String, String> next = new LinkedHashMap<>();
            body.path("continue").fields().forEachRemaining(f -> next.put(f.getKey(), f.getValue().asText()));
            cont = next;
        }
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        merged.putObject("query").putArray("pages").addAll(pages.values());
        return parse(merged, center, categories.isEmpty() ? SIGHTSEEING : categories);
    }

    static List<Place> parse(JsonNode body, GeoPoint center, Set<String> wanted) {
        List<Place> places = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode page : body == null ? List.<JsonNode>of() : body.path("query").path("pages")) {
            String title = page.path("title").asText("");
            JsonNode coords = page.path("coordinates").path(0);
            if (title.isBlank() || coords.isMissingNode()) {
                continue;
            }
            String category = classify(page.path("description").asText(""), title);
            String name = displayName(title);
            if (category == null || !wanted.contains(category) || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String qid = page.path("pageprops").path("wikibase_item").asText("");
            String id = qid.matches("Q\\d+") ? "wiki-" + qid : "wiki-page-" + page.path("pageid").asText();
            LocalTime[] estimate = CategoryDefaults.hours(category);
            places.add(new Place(id, name, category, coords.path("lat").asDouble(), coords.path("lon").asDouble(),
                    popularity(pageViews(page)), CategoryDefaults.visitMinutes(category), estimate[0], estimate[1],
                    null, 0, Set.of(), Place.ESTIMATED).withDistanceFrom(center));
        }
        return places;
    }

    /**
     * The short description is the most reliable signal ("Palace on an island in Lake Pichola"), so
     * categories are matched against it first and the title only as a fallback ("Jag Mandir" alone
     * would read as a temple). Exclusions apply to the description.
     */
    static String classify(String description, String title) {
        String desc = description.toLowerCase(Locale.ROOT);
        String fromDescription = match(desc);
        if (fromDescription != null) {
            return fromDescription;
        }
        if (!desc.isBlank() && EXCLUDE.matcher(desc).find()) {
            return null;
        }
        return match(title.toLowerCase(Locale.ROOT));
    }

    private static String match(String text) {
        for (Map.Entry<String, Pattern> e : CATEGORIES.entrySet()) {
            if (e.getValue().matcher(text).find()) {
                return e.getKey();
            }
        }
        return null;
    }

    static long pageViews(JsonNode page) {
        long total = 0;
        for (JsonNode day : page.path("pageviews")) {
            total += day.asLong(0);
        }
        return total;
    }

    /** 30-day page views -> 3.2..4.8 on a log scale (~100 -> 3.8, ~2k -> 4.3, ~100k+ -> 4.8). */
    static double popularity(long views) {
        double score = 3.2 + 0.32 * Math.log10(views + 1);
        return Math.round(Math.min(4.8, score) * 10) / 10.0;
    }

    /** "Jagdish Temple, Udaipur" -> "Jagdish Temple"; "Chandpole (Udaipur)" -> "Chandpole". */
    static String displayName(String title) {
        String name = title.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        int comma = name.lastIndexOf(", ");
        if (comma > 3) {
            name = name.substring(0, comma);
        }
        return name.trim();
    }
}
