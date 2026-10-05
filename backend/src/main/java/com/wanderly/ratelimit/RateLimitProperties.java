package com.wanderly.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-IP limits on the endpoints worth abusing (D58). Each is a token bucket: up to {@code capacity}
 * requests in a burst, refilled evenly so that {@code capacity} more are allowed every {@code per}.
 */
@ConfigurationProperties(prefix = "wanderly.rate-limit")
public record RateLimitProperties(boolean enabled, Limit login, Limit signup, Limit code, Limit planner) {

    public record Limit(int capacity, Duration per) {

        /** Tokens added per millisecond. */
        double refillPerMs() {
            return (double) capacity / per.toMillis();
        }
    }
}
