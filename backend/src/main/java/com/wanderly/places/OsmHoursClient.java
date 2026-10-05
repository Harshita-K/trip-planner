package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenStreetMap {@code opening_hours} for known OSM objects (by OSM id or Wikidata id). Answers,
 * including "none tagged", are cached per object for 7 days; failures are never cached.
 *
 * <p>User requests never wait on Overpass: {@link #cachedOrRefreshInBackground} returns whatever is
 * cached right away and fetches the rest on a virtual thread (deduplicated), so the <em>next</em>
 * request for that area gets real hours. The synchronous methods are for tests and tooling.
 */
@Component
public class OsmHoursClient {

    private static final Logger log = LoggerFactory.getLogger(OsmHoursClient.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final int BATCH = 100;

    private final OverpassClient overpass;
    private final StringRedisTemplate redis;
    private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What a request may know right now, without waiting on Overpass. */
    public record Lookup(Map<String, String> byOsmRef, Map<String, String> byWikidata) {
    }

    /**
     * Cached hours only; anything not yet cached is fetched in the background.
     * @param osmRefs OSM refs like {@code way/123}
     * @param qids    Wikidata ids, looked up inside {@code bbox}
     */
    public Lookup cachedOrRefreshInBackground(List<String> osmRefs, List<String> qids, String bbox) {
        Map<String, String> byRef = new HashMap<>();
        Map<String, String> byQid = new HashMap<>();
        List<String> missingRefs = splitCached(osmRefs, "", byRef);
        List<String> missingQids = splitCached(qids, "wd:", byQid);
        if (!missingRefs.isEmpty() && inFlight.add("refs:" + String.join(",", missingRefs))) {
            String key = "refs:" + String.join(",", missingRefs);
            background.submit(() -> { try { hoursFor(missingRefs); } finally { inFlight.remove(key); } });
        }
        if (!missingQids.isEmpty() && inFlight.add("wd:" + String.join(",", missingQids))) {
            String key = "wd:" + String.join(",", missingQids);
            background.submit(() -> { try { hoursForWikidata(missingQids, bbox); } finally { inFlight.remove(key); } });
        }
        return new Lookup(byRef, byQid);
    }

    /** Fills {@code found} from cache; returns the ids not cached yet. */
    private List<String> splitCached(List<String> ids, String prefix, Map<String, String> found) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        List<String> cached = cacheGetAll(ids.stream().map(id -> prefix + id).toList());
        for (int i = 0; i < ids.size(); i++) {
            if (cached.get(i) == null) {
                missing.add(ids.get(i));
            } else if (!cached.get(i).isEmpty()) {
                found.put(ids.get(i), cached.get(i));
            }
        }
        return missing;
    }

    public OsmHoursClient(OverpassClient overpass, StringRedisTemplate redis) {
        this.overpass = overpass;
        this.redis = redis;
    }

    /** @param refs OSM refs like {@code node/123}. @return ref -> raw opening_hours, for refs that have it (best effort). */
    public Map<String, String> hoursFor(List<String> refs) {
        Map<String, String> found = new HashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> cached = cacheGetAll(refs);
        for (int i = 0; i < refs.size(); i++) {
            if (cached.get(i) == null) {
                missing.add(refs.get(i));
            } else if (!cached.get(i).isEmpty()) {
                found.put(refs.get(i), cached.get(i));
            }
        }
        for (int i = 0; i < missing.size(); i += BATCH) {
            List<String> batch = missing.subList(i, Math.min(missing.size(), i + BATCH));
            Optional<JsonNode> body = overpass.query(byIdQuery(batch), BUDGET);
            if (body.isEmpty()) {
                break;   // Overpass down: estimates stay, nothing cached, retried next time
            }
            Map<String, String> fetched = new HashMap<>();
            for (JsonNode el : body.get().path("elements")) {
                String hours = el.path("tags").path("opening_hours").asText("");
                if (!hours.isBlank()) {
                    fetched.put(el.path("type").asText() + "/" + el.path("id").asText(), hours);
                }
            }
            batch.forEach(ref -> cachePut(ref, fetched.getOrDefault(ref, "")));
            found.putAll(fetched);
        }
        return found;
    }

    /**
     * Opening hours for places known by Wikidata id (Wikipedia sights): matched to OSM objects tagged
     * {@code wikidata=Q…} inside {@code bbox}. Cached per id for 7 days; failures not cached.
     * @return Wikidata id -> raw opening_hours
     */
    public Map<String, String> hoursForWikidata(List<String> qids, String bbox) {
        Map<String, String> found = new HashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> cached = cacheGetAll(qids.stream().map(q -> "wd:" + q).toList());
        for (int i = 0; i < qids.size(); i++) {
            if (cached.get(i) == null) {
                missing.add(qids.get(i));
            } else if (!cached.get(i).isEmpty()) {
                found.put(qids.get(i), cached.get(i));
            }
        }
        if (missing.isEmpty()) {
            return found;
        }
        StringBuilder q = new StringBuilder("[out:json][timeout:10][bbox:").append(bbox).append("];(");
        missing.forEach(id -> q.append("nwr[\"wikidata\"=\"").append(id).append("\"];"));
        q.append(");out tags;");
        Optional<JsonNode> body = overpass.query(q.toString(), BUDGET);
        if (body.isEmpty()) {
            return found;
        }
        Map<String, String> fetched = new HashMap<>();
        for (JsonNode el : body.get().path("elements")) {
            String hours = el.path("tags").path("opening_hours").asText("");
            String qid = el.path("tags").path("wikidata").asText("");
            if (!hours.isBlank() && !qid.isBlank()) {
                fetched.putIfAbsent(qid, hours);
            }
        }
        missing.forEach(id -> cachePut("wd:" + id, fetched.getOrDefault(id, "")));
        found.putAll(fetched);
        return found;
    }

    static String byIdQuery(List<String> refs) {
        Map<String, List<String>> idsByType = new HashMap<>();
        for (String ref : refs) {
            String[] parts = ref.split("/");
            if (parts.length == 2 && List.of("node", "way", "relation").contains(parts[0]) && parts[1].matches("\\d+")) {
                idsByType.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
            }
        }
        StringBuilder q = new StringBuilder("[out:json][timeout:10];(");
        idsByType.forEach((type, ids) -> q.append(type).append("(id:").append(String.join(",", ids)).append(");"));
        return q.append(");out tags;").toString();
    }

    private List<String> cacheGetAll(List<String> refs) {
        try {
            List<String> values = redis.opsForValue().multiGet(refs.stream().map(r -> "osmhours:" + r).toList());
            if (values != null && values.size() == refs.size()) {
                return values;
            }
        } catch (RuntimeException e) {
            log.debug("Hours cache unavailable: {}", e.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(refs.size(), null));
    }

    private void cachePut(String ref, String hours) {
        try {
            redis.opsForValue().set("osmhours:" + ref, hours, CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Could not cache hours for {}: {}", ref, e.getMessage());
        }
    }
}
