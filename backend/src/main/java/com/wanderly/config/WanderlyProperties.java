package com.wanderly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "wanderly")
public record WanderlyProperties(Security security, Kafka kafka, Places places) {

    /** {@code cookieSecure}: mark the session cookie Secure (HTTPS only). Off for http://localhost. */
    public record Security(String jwtSecret, Duration tokenTtl, String otpSecret, boolean cookieSecure) {
    }

    public record Kafka(int partitions) {
    }

    /** {@code live}: fetch places for non-curated cities (Overpass, or OpenTripMap with a key). */
    public record Places(Duration cacheTtl, boolean live, OpenTripMap opentripmap, Osm osm) {
    }

    public record OpenTripMap(String apiKey, String baseUrl) {
    }

    /** Open-data services (OpenStreetMap, Wikipedia). Their usage policies ask clients to identify themselves (contact). */
    public record Osm(String overpassUrl, String photonUrl, String wikipediaUrl, String contact) {

        public String userAgent() {
            return "Wanderly/0.1 (trip planner" + (contact == null || contact.isBlank() ? "" : "; " + contact) + ")";
        }
    }
}
