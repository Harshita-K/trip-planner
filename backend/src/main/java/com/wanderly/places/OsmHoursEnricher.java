package com.wanderly.places;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Upgrades estimated hours to real OpenStreetMap hours for live places. Runs after the places-list
 * cache (not inside it), so a temporary Overpass outage never freezes estimates into the 24 h cache,
 * and never blocks the request: uncached hours are fetched in the background for next time.
 * OpenTripMap places are matched by OSM id ({@code osm-way-123} -> {@code way/123}); Wikipedia places
 * by Wikidata id ({@code wiki-Q123} -> OSM objects tagged {@code wikidata=Q123}).
 */
@Component
public class OsmHoursEnricher {

    private final OsmHoursClient client;

    public OsmHoursEnricher(OsmHoursClient client) {
        this.client = client;
    }

    /**
     * @param byOsmId also look up places with OSM ids ({@code osm-way-123}); used for OpenTripMap.
     *                Wikipedia places ({@code wiki-Q123}) are always looked up via their Wikidata id.
     */
    public List<Place> enrich(List<Place> places, boolean byOsmId) {
        List<Place> estimated = places.stream().filter(p -> Place.ESTIMATED.equals(p.hoursSource())).toList();
        Map<String, String> refById = new LinkedHashMap<>();
        Map<String, String> qidById = new LinkedHashMap<>();
        for (Place p : estimated) {
            String ref = byOsmId ? osmRef(p.id()) : null;
            if (ref != null) {
                refById.put(p.id(), ref);
            } else if (p.id().matches("wiki-Q\\d+")) {
                qidById.put(p.id(), p.id().substring(5));
            }
        }
        if (refById.isEmpty() && qidById.isEmpty()) {
            return places;
        }
        List<Place> wiki = estimated.stream().filter(p -> qidById.containsKey(p.id())).toList();
        OsmHoursClient.Lookup known = client.cachedOrRefreshInBackground(
                new ArrayList<>(new java.util.LinkedHashSet<>(refById.values())),
                new ArrayList<>(new java.util.LinkedHashSet<>(qidById.values())),
                wiki.isEmpty() ? null : bbox(wiki));

        List<Place> result = new ArrayList<>(places.size());
        for (Place p : places) {
            String raw = refById.containsKey(p.id()) ? known.byOsmRef().get(refById.get(p.id()))
                    : qidById.containsKey(p.id()) ? known.byWikidata().get(qidById.get(p.id())) : null;
            result.add(raw == null ? p : OpeningHours.parse(raw)
                    .map(w -> p.withHours(w.opens(), w.closes(), w.closedOn(), Place.OSM))
                    .orElse(p));
        }
        return result;
    }

    /** Bounding box around the places, padded by ~1 km, in Overpass order (south,west,north,east). */
    static String bbox(List<Place> places) {
        double s = places.stream().mapToDouble(Place::lat).min().orElse(0) - 0.01;
        double w = places.stream().mapToDouble(Place::lng).min().orElse(0) - 0.01;
        double n = places.stream().mapToDouble(Place::lat).max().orElse(0) + 0.01;
        double e = places.stream().mapToDouble(Place::lng).max().orElse(0) + 0.01;
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", s, w, n, e);
    }

    /** {@code osm-way-123} -> {@code way/123}; null for ids that don't reference an OSM object. */
    static String osmRef(String placeId) {
        if (placeId == null || !placeId.startsWith("osm-")) {
            return null;
        }
        String[] parts = placeId.split("-");
        return parts.length == 3 ? parts[1] + "/" + parts[2] : null;
    }

    static String placeId(String osmRef) {
        return "osm-" + osmRef.replace('/', '-');
    }
}
