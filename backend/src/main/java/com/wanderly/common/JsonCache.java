package com.wanderly.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cache-aside helper over Redis that stores values as JSON.
 *
 * <p>Redis is an optimisation, never a dependency: if Redis is down or a payload fails to
 * deserialise, we log and fall through to the loader so the request still succeeds.
 */
@Component
public class JsonCache {

    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        Optional<T> cached = get(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }
        T value = loader.get();
        put(key, value, ttl);
        return value;
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Cache read failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache write failed for {}: {}", key, e.getMessage());
        }
    }
}
