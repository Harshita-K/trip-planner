package com.wanderly.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Token buckets in Redis, shared by every app instance (D58).
 *
 * <pre>ratelimit:{rule}:{ip}   HASH { tokens, ts }   expires once it would be full again</pre>
 *
 * The whole check (refill by elapsed time, take a token or compute the wait) is one Lua script, so
 * concurrent requests can't both take the last token. Time comes from Redis ({@code TIME}), not the
 * app servers, so clock skew between instances doesn't matter.
 *
 * <p>Fails open: if Redis is unreachable the request is allowed. Rate limiting protects against
 * abuse; it shouldn't take the login page down with it.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Returns 0 if allowed, else milliseconds until a token is available. */
    private static final RedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1]) or capacity
            local ts = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local wait = 0
            if tokens < 1 then
              wait = math.ceil((1 - tokens) / rate)
            else
              tokens = tokens - 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate))
            return wait
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return {@link Duration#ZERO} if allowed, else how long until the next request would be. */
    public Duration take(String rule, String client, RateLimitProperties.Limit limit) {
        try {
            Long waitMs = redis.execute(TAKE, List.of("ratelimit:" + rule + ":" + client),
                    Integer.toString(limit.capacity()), Double.toString(limit.refillPerMs()));
            return waitMs == null || waitMs <= 0 ? Duration.ZERO : Duration.ofMillis(waitMs);
        } catch (RuntimeException e) {
            log.warn("Rate limit check skipped (Redis unavailable): {}", e.getMessage());
            return Duration.ZERO;
        }
    }
}
