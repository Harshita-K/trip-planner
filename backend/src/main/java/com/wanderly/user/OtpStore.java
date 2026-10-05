package com.wanderly.user;

import com.wanderly.config.WanderlyProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One-time email codes in Redis, for sign-up verification and password reset. Each {@link Purpose}
 * has its own keys, so a sign-up code can never reset a password or the other way round.
 *
 * <pre>
 * otp:{email}               HASH { hash, attempts, id }   TTL 10 min   (pending sign-up code)
 * otp:cooldown:{email}      STRING                        TTL 60 s     (resend throttle)
 * pwreset:{email}           HASH { hash, attempts, id }   TTL 10 min   (pending password-reset code)
 * pwreset:cooldown:{email}  STRING                        TTL 60 s
 * otp:notice:{email}        STRING                        TTL 60 s     ("account exists" email throttle)
 * </pre>
 *
 * Every read-modify-write runs as a Lua script, which Redis executes atomically: concurrent
 * requests can't both slip under the attempt limit or both consume the same code.
 *
 * <p>Codes are stored as HMAC-SHA256(secret, email:code), not plain text. HMAC rather than BCrypt
 * because the comparison has to happen <em>inside</em> the Lua script to stay atomic, which needs a
 * deterministic hash; brute force is already bounded by 5 attempts on a 10^6 space.
 */
@Component
public class OtpStore {

    public enum VerifyResult { OK, WRONG, LOCKED, INVALID }

    public enum Purpose {
        VERIFY_EMAIL("otp:"), RESET_PASSWORD("pwreset:");

        private final String prefix;

        Purpose(String prefix) {
            this.prefix = prefix;
        }
    }

    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;

    /** Set the cooldown (only if free) and store a fresh code, replacing any older one. 1 = issued, 0 = cooling down. */
    private static final RedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            if not redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[4]) then
              return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'attempts', 0, 'id', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    /**
     * Compare, count and consume in one atomic step. A match deletes the code (single use);
     * the MAX_ATTEMPTS-th miss deletes it too (burned).
     */
    private static final RedisScript<String> VERIFY = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'hash')
            if not stored then
              return 'INVALID'
            end
            if stored == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 'OK'
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              return 'LOCKED'
            end
            return 'WRONG'
            """, String.class);

    private final StringRedisTemplate redis;
    private final SecretKeySpec hmacKey;

    public OtpStore(StringRedisTemplate redis, WanderlyProperties props) {
        this.redis = redis;
        byte[] secret = props.security().otpSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("wanderly.security.otp-secret must be at least 32 bytes");
        }
        this.hmacKey = new SecretKeySpec(secret, "HmacSHA256");
    }

    /** Sign-up verification code. @see #issue(Purpose, String, String, String) */
    public boolean issue(String email, String code, String otpId) {
        return issue(Purpose.VERIFY_EMAIL, email, code, otpId);
    }

    /** @return false if a code for this purpose was issued to this email less than {@link #COOLDOWN} ago. */
    public boolean issue(Purpose purpose, String email, String code, String otpId) {
        Long result = redis.execute(ISSUE, List.of(otpKey(purpose, email), cooldownKey(purpose, email)),
                hash(purpose, email, code), otpId, Long.toString(TTL.toSeconds()), Long.toString(COOLDOWN.toSeconds()));
        return result != null && result == 1L;
    }

    public VerifyResult verify(String email, String code) {
        return verify(Purpose.VERIFY_EMAIL, email, code);
    }

    public VerifyResult verify(Purpose purpose, String email, String code) {
        String result = redis.execute(VERIFY, List.of(otpKey(purpose, email)), hash(purpose, email, code),
                Integer.toString(MAX_ATTEMPTS));
        return result == null ? VerifyResult.INVALID : VerifyResult.valueOf(result);
    }

    /** Id of the currently valid code, or null if none (expired, used or burned). */
    public String currentOtpId(Purpose purpose, String email) {
        Object id = redis.opsForHash().get(otpKey(purpose, email), "id");
        return id == null ? null : id.toString();
    }

    /** Throttle for emails that aren't codes (e.g. "you already have an account"). */
    public boolean claimCooldown(String email) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("otp:notice:" + normalise(email), "1", COOLDOWN));
    }

    /** Sign-up codes hash {@code email:code} (unchanged since D38); other purposes are domain-separated. */
    String hash(Purpose purpose, String email, String code) {
        String input = (purpose == Purpose.VERIFY_EMAIL ? "" : purpose.prefix) + normalise(email) + ":" + code;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");   // Mac isn't thread-safe; cheap to create
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String otpKey(Purpose purpose, String email) {
        return purpose.prefix + normalise(email);
    }

    private static String cooldownKey(Purpose purpose, String email) {
        return purpose.prefix + "cooldown:" + normalise(email);
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
