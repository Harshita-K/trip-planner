package com.wanderly.places;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wanderly.common.ApiException;
import com.wanderly.common.GeoPoint;
import com.wanderly.common.JsonCache;
import com.wanderly.config.WanderlyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Internal façade over the places sources, so no other module talks to an external API directly.
 *
 * <p>Routing: inside a curated city (Bengaluru, Jaipur) the hand-checked dataset is used. Anywhere
 * else, live data (OpenTripMap + OpenStreetMap hours) is used when an API key is configured.
 * Live place lists are cached in Redis on a ~1.1 km grid (coordinates rounded to 2 decimals), so nearby
 * searches from almost the same spot share one upstream call, which keeps us inside free-tier limits.
 */
@Service
public class PlacesService {

    private static final Logger log = LoggerFactory.getLogger(PlacesService.class);
    private static final TypeReference<List<Place>> PLACE_LIST = new TypeReference<>() {
    };
    /** Max distance between a query point and its grid cell centre is ~0.8 km; over-fetch by that. */
    private static final double GRID_PADDING_KM = 1.0;

    private final SeedPlacesProvider curated;
    private final BundledOsmPlaces bundled;
    private final PlacesProvider live;   // null when live data is switched off (wanderly.places.live=false)
    private final OsmHoursEnricher hours;
    private final JsonCache cache;
    private final WanderlyProperties props;

    /**
     * Live source: OpenTripMap when an API key is configured (its places then get OSM hours via the
     * enricher), otherwise open data with no key at all: Wikipedia for sights, Overpass for food.
     */
    public PlacesService(SeedPlacesProvider curated, BundledOsmPlaces bundled,
                         ObjectProvider<OpenTripMapPlacesProvider> openTripMap, OpenDataPlacesProvider openData,
                         OsmHoursEnricher hours, JsonCache cache, WanderlyProperties props) {
        this.curated = curated;
        this.bundled = bundled;
        PlacesProvider otm = openTripMap.getIfAvailable();
        this.live = !props.places().live() ? null : otm != null ? otm : openData;
        this.hours = hours;
        this.cache = cache;
        this.props = props;
    }

    public List<Place> nearby(GeoPoint origin, double radiusKm, Set<String> categories, int limit) {
        Set<String> normalised = normalise(categories);
        Stream<Place> results;
        if (curated.covers(origin) || (live == null && !bundled.covers(origin))) {
            results = curated.nearby(origin, radiusKm, normalised).stream();
        } else if (bundled.covers(origin)) {
            results = bundled.nearby(origin, radiusKm, normalised).stream();   // pre-fetched OSM: instant, no network
        } else {
            results = liveNearby(origin, radiusKm, normalised).stream()
                    .map(p -> p.withDistanceFrom(origin))
                    .filter(p -> p.distanceKm() <= radiusKm);
            List<Place> top = results.sorted(Comparator.comparingDouble(Place::distanceKm)).limit(limit).toList();
            // OpenTripMap and Wikipedia have no hours: layer OSM hours on after the cache (never cached as estimates).
            return hours.enrich(top, live instanceof OpenTripMapPlacesProvider);
        }
        return results.sorted(Comparator.comparingDouble(Place::distanceKm)).limit(limit).toList();
    }

    public Coverage coverage() {
        return new Coverage(live != null, live == null ? null : live.name(), curated.cities(), bundled.cityNames().size());
    }

    public boolean isCurated(GeoPoint point) {
        return curated.covers(point);
    }

    /** True if places for this point are available without a live API call (curated or bundled OSM). */
    public boolean isAvailableOffline(GeoPoint point) {
        return curated.covers(point) || bundled.covers(point);
    }

    /** {@code liveSource}: "osm" (Overpass, keyless), "otm" (OpenTripMap) or null when off. */
    public record Coverage(boolean liveData, String liveSource, Set<String> curatedCities, int bundledCities) {
    }

    private List<Place> liveNearby(GeoPoint origin, double radiusKm, Set<String> categories) {
        GeoPoint cell = new GeoPoint(round2(origin.lat()), round2(origin.lng()));
        double fetchRadius = Math.ceil(radiusKm) + GRID_PADDING_KM;
        String key = String.format(Locale.ROOT, "places:%s:%.2f,%.2f:r%.0f:%s", live.name(), cell.lat(),
                cell.lng(), fetchRadius, categories.isEmpty() ? "all" : String.join("+", categories));
        try {
            if (live instanceof OpenDataPlacesProvider openData) {
                return cache.get(key, PLACE_LIST).orElseGet(() -> {
                    OpenDataPlacesProvider.Fetch fetch = openData.fetch(cell, fetchRadius, categories);
                    if (fetch.complete()) {
                        cache.put(key, fetch.places(), props.places().cacheTtl());   // partial results are retried next time
                    }
                    return fetch.places();
                });
            }
            return cache.getOrLoad(key, PLACE_LIST, props.places().cacheTtl(),
                    () -> live.nearby(cell, fetchRadius, categories));
        } catch (RestClientException e) {
            log.warn("Live places lookup failed near {}: {}", cell, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Live place data is unavailable right now. Please try again shortly.");
        }
    }

    private static Set<String> normalise(Set<String> categories) {
        Set<String> sorted = new TreeSet<>();
        if (categories != null) {
            categories.stream().filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().toLowerCase(Locale.ROOT))
                    .forEach(sorted::add);
        }
        return sorted;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
