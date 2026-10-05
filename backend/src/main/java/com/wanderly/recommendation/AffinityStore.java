package com.wanderly.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-user category affinity, kept as a Redis sorted set {@code affinity:{userId}} and fed from
 * the user-activity stream. This is the "real-time touch" next to the offline batch model: it
 * reacts to what a user did minutes ago without retraining anything.
 */
@Component
public class AffinityStore {

    private static final Logger log = LoggerFactory.getLogger(AffinityStore.class);
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;

    public AffinityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void increment(UUID userId, String category, double weight) {
        String key = key(userId);
        redis.opsForZSet().incrementScore(key, category, weight);
        redis.expire(key, TTL);
    }

    /** Category -> affinity normalised to 0..1 (strongest category = 1). Empty if unknown or Redis is down. */
    public Map<String, Double> normalised(UUID userId) {
        if (userId == null) {
            return Map.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key(userId), 0, 19);
            if (tuples == null || tuples.isEmpty()) {
                return Map.of();
            }
            double max = tuples.stream().mapToDouble(t -> t.getScore() == null ? 0 : t.getScore()).max().orElse(0);
            if (max <= 0) {
                return Map.of();
            }
            Map<String, Double> result = new HashMap<>();
            tuples.forEach(t -> result.put(t.getValue(), (t.getScore() == null ? 0 : t.getScore()) / max));
            return result;
        } catch (Exception e) {
            log.warn("Affinity lookup failed for {}: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    private static String key(UUID userId) {
        return "affinity:" + userId;
    }
}
