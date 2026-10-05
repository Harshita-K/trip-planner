package com.wanderly.travel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wanderly.common.ApiException;
import com.wanderly.common.GeoPoint;
import com.wanderly.common.JsonCache;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F8: all the ways to get from A to B. The independent lookups (road route from OSRM, airports at
 * each end) run in parallel with {@link CompletableFuture} on virtual threads, the design doc's
 * fan-out pattern, which would extend naturally to real per-mode fare APIs. Plans are cached for
 * 1 hour (fares change; routes are cached much longer inside {@link RoadRouter}).
 */
@Service
public class TravelService {

    private static final TypeReference<TravelDtos.TravelPlan> PLAN = new TypeReference<>() {
    };
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final RoadRouter roads;
    private final AirportDirectory airports;
    private final JsonCache cache;
    private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

    public TravelService(RoadRouter roads, AirportDirectory airports, JsonCache cache) {
        this.roads = roads;
        this.airports = airports;
        this.cache = cache;
    }

    public TravelDtos.TravelPlan plan(String fromName, GeoPoint from, String toName, GeoPoint to, LocalDate date, int travellers) {
        LocalDate today = LocalDate.now(INDIA);
        if (date.isBefore(today)) {
            throw ApiException.badRequest("Pick today or a future date.");
        }
        if (from.distanceKm(to) < 15) {
            throw ApiException.badRequest("Origin and destination are the same place.");
        }
        String key = String.format(Locale.ROOT, "travel:%.3f,%.3f:%.3f,%.3f:%s:%d", from.lat(), from.lng(), to.lat(), to.lng(),
                date, travellers);
        return cache.getOrLoad(key, PLAN, CACHE_TTL, () -> {
            CompletableFuture<RoadRouter.Route> road = CompletableFuture.supplyAsync(() -> roads.route(from, to), fanOut);
            CompletableFuture<Optional<Airport>> fromAirport = CompletableFuture.supplyAsync(() -> airports.nearest(from), fanOut);
            CompletableFuture<Optional<Airport>> toAirport = CompletableFuture.supplyAsync(() -> airports.nearest(to), fanOut);
            try {
                CompletableFuture.allOf(road, fromAirport, toAirport).join();
                return TravelPlanner.plan(
                        new TravelPlanner.Place(fromName, from, fromAirport.join()),
                        new TravelPlanner.Place(toName, to, toAirport.join()),
                        date, today, travellers, road.join());
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        });
    }
}
