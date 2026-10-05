package com.wanderly.user;

import com.wanderly.config.WanderlyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Sign out everywhere" for stateless JWTs: after a password reset, tokens issued before that moment
 * are rejected. One Redis key per affected user, {@code auth:revoked-before:{userId}} = epoch millis,
 * kept only for the token lifetime (older tokens have expired by then anyway).
 *
 * <p>Fails open: if Redis is unreachable the token's signature and expiry still apply. This is the
 * same trade-off as everywhere else Redis is used, and the token TTL bounds the exposure.
 */
@Component
public class TokenRevocation implements OAuth2TokenValidator<Jwt> {

    /** Issue time in milliseconds. The standard {@code iat} is whole seconds, too coarse to tell apart
     *  a token from just before a reset and the one issued by it. */
    public static final String ISSUED_AT_MS = "iat_ms";

    private static final Logger log = LoggerFactory.getLogger(TokenRevocation.class);
    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "This session has ended. Please log in again.", null);

    private final StringRedisTemplate redis;
    private final Duration tokenTtl;

    public TokenRevocation(StringRedisTemplate redis, WanderlyProperties props) {
        this.redis = redis;
        this.tokenTtl = props.security().tokenTtl();
    }

    /** Tokens issued (strictly) before {@code at} stop working. */
    public void revokeIssuedBefore(UUID userId, Instant at) {
        redis.opsForValue().set(key(userId), Long.toString(at.toEpochMilli()), tokenTtl);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getIssuedAt() == null) {
            return OAuth2TokenValidatorResult.success();
        }
        String cutoff;
        try {
            cutoff = redis.opsForValue().get(key(jwt.getSubject()));
        } catch (RuntimeException e) {
            log.warn("Revocation check skipped (Redis unavailable): {}", e.getMessage());
            return OAuth2TokenValidatorResult.success();
        }
        return cutoff != null && issuedAtMillis(jwt) < Long.parseLong(cutoff)
                ? OAuth2TokenValidatorResult.failure(REVOKED)
                : OAuth2TokenValidatorResult.success();
    }

    private static long issuedAtMillis(Jwt jwt) {
        Object ms = jwt.getClaims().get(ISSUED_AT_MS);
        return ms instanceof Number n ? n.longValue() : jwt.getIssuedAt().toEpochMilli();
    }

    private static String key(Object userId) {
        return "auth:revoked-before:" + userId;
    }
}
