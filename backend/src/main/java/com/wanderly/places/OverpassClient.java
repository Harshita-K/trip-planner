package com.wanderly.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.wanderly.config.WanderlyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Shared, polite access to the public Overpass API (OpenStreetMap), used for both places and
 * opening hours. Public servers are free but shared and often overloaded (504 "too busy"), so:
 * <ul>
 *   <li>Failover across {@code OVERPASS_URL} servers (comma-separated), within a time budget.</li>
 *   <li>At most 2 requests in flight (Overpass grants ~2 slots per client IP).</li>
 *   <li>Circuit breaker: after a failure every caller skips Overpass for 2 minutes and falls back.</li>
 *   <li>Identifying User-Agent, as the OSM usage policies ask.</li>
 * </ul>
 */
@Component
public class OverpassClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);
    private static final Duration BREAKER = Duration.ofMinutes(2);

    private final List<RestClient> servers;
    private final Semaphore slots = new Semaphore(2);
    private volatile long skipUntil;

    public OverpassClient(RestClient.Builder builder, WanderlyProperties props) {
        SimpleClientHttpRequestFactory perServer = new SimpleClientHttpRequestFactory();
        perServer.setConnectTimeout(4_000);
        perServer.setReadTimeout(12_000);   // a busy server must not hold a user request for long
        this.servers = Arrays.stream(props.places().osm().overpassUrl().split(","))
                .map(String::trim).filter(u -> !u.isEmpty())
                .map(url -> builder.clone().baseUrl(url).requestFactory(perServer)
                        .defaultHeader("User-Agent", props.places().osm().userAgent()).build())
                .toList();
    }

    /** Run an Overpass QL query. Empty if no server answered within {@code budget} or the breaker is open. */
    public Optional<JsonNode> query(String overpassQl, Duration budget) {
        if (System.currentTimeMillis() < skipUntil) {
            return Optional.empty();
        }
        long deadline = System.currentTimeMillis() + budget.toMillis();
        try {
            if (!slots.tryAcquire(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("data", overpassQl);
            for (RestClient server : servers) {
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
                try {
                    JsonNode body = server.post().contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                            .retrieve().body(JsonNode.class);
                    // Overpass reports query timeouts / memory errors as HTTP 200 with a "remark", not an error status.
                    String remark = body == null ? "" : body.path("remark").asText("");
                    if (body != null && body.has("elements") && !remark.contains("error")) {
                        return Optional.of(body);
                    }
                    log.info("Overpass server returned no usable result: {}", remark);
                } catch (RestClientException e) {
                    log.info("Overpass server failed, trying next: {}", e.getMessage());
                }
            }
            skipUntil = System.currentTimeMillis() + BREAKER.toMillis();
            log.warn("Overpass unavailable; skipping it for {} min", BREAKER.toMinutes());
            return Optional.empty();
        } finally {
            slots.release();
        }
    }
}
