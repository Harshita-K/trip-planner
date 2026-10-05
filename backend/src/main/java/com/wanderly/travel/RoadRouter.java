package com.wanderly.travel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.wanderly.common.GeoPoint;
import com.wanderly.common.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Real road distance and driving time from OSRM (OpenStreetMap routing, no API key). The public
 * demo server is shared, so routes are cached for 30 days (roads rarely change), calls are capped at
 * one in flight, and any failure falls back to a straight-line estimate flagged as such.
 * Production would self-host OSRM; set {@code OSRM_URL}, or leave it empty to always estimate.
 */
@Component
public class RoadRouter {

    public record Route(double km, double hours, boolean estimated) {
    }

    private static final Logger log = LoggerFactory.getLogger(RoadRouter.class);
    private static final TypeReference<Route> ROUTE = new TypeReference<>() {
    };
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    private final RestClient osrm;
    private final JsonCache cache;
    private final Semaphore oneAtATime = new Semaphore(1);

    public RoadRouter(RestClient.Builder builder, JsonCache cache, @Value("${wanderly.travel.osrm-url:}") String osrmUrl) {
        this.osrm = osrmUrl.isBlank() ? null : builder.clone().baseUrl(osrmUrl)
                .defaultHeader("User-Agent", "Wanderly/0.1 (trip planner)").build();
        this.cache = cache;
    }

    public Route route(GeoPoint from, GeoPoint to) {
        String key = String.format(Locale.ROOT, "road:%.3f,%.3f:%.3f,%.3f", from.lat(), from.lng(), to.lat(), to.lng());
        return cache.get(key, ROUTE).orElseGet(() -> {
            Route route = callOsrm(from, to);
            if (route == null) {
                return estimate(from, to);   // not cached, so OSRM is tried again next time
            }
            cache.put(key, route, CACHE_TTL);
            return route;
        });
    }

    private Route callOsrm(GeoPoint from, GeoPoint to) {
        if (osrm == null) {
            return null;
        }
        try {
            if (!oneAtATime.tryAcquire(5, TimeUnit.SECONDS)) {
                return null;
            }
            try {
                JsonNode body = osrm.get()
                        .uri(String.format(Locale.ROOT, "/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=false",
                                from.lng(), from.lat(), to.lng(), to.lat()))
                        .retrieve().body(JsonNode.class);
                JsonNode r = body == null ? null : body.path("routes").path(0);
                if (r == null || r.isMissingNode() || !"Ok".equals(body.path("code").asText())) {
                    return null;   // e.g. no road connection (islands)
                }
                return new Route(round1(r.path("distance").asDouble() / 1000), round2(r.path("duration").asDouble() / 3600), false);
            } finally {
                oneAtATime.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.warn("OSRM route failed, estimating: {}", e.getMessage());
            return null;
        }
    }

    /** Straight line x 1.35 winding factor at an average 50 km/h. */
    static Route estimate(GeoPoint from, GeoPoint to) {
        double km = from.distanceKm(to) * 1.35;
        return new Route(round1(km), round2(km / 50.0), true);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
