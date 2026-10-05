package com.wanderly.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.messaging.CodeCipher;
import com.wanderly.messaging.EmailJob;
import com.wanderly.messaging.Topics;
import com.wanderly.user.OtpStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Consumer group {@code email-worker} on {@code emails}: does the slow SMTP work off the request path.
 *
 * <p><b>Idempotency under at-least-once delivery.</b> Kafka may redeliver a job, for example when a
 * worker sends the email and then crashes before committing its offset. Each job id moves through a
 * Redis state key {@code email:job:{id}}:
 * <pre>
 *   (absent) --SET NX, 30 s lease--> sending:{token} --after SMTP accepts--> sent (kept 24 h)
 * </pre>
 * <ul>
 *   <li>{@code sent}: duplicate delivery, skip.</li>
 *   <li>{@code sending}: another attempt is mid-send. Retry later (see KafkaConfig backoff).</li>
 *   <li>SMTP failure: release the lease (compare-and-delete with our token) and let Kafka retry.</li>
 *   <li>Crash between "SMTP accepted" and "mark sent": the lease expires and the job is sent once
 *       more. Exactly-once email delivery is impossible over SMTP, but this duplicate is harmless:
 *       same code, same Message-ID, and the code's state in Redis (validity, attempts) is untouched.</li>
 * </ul>
 * Code jobs (sign-up and password reset) also skip if the code is no longer the current one (replaced, used or expired), so a
 * late redelivery never emails a dead code.
 */
@Component
public class EmailWorker {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration DONE_TTL = Duration.ofHours(24);
    private static final String SENT = "sent";

    /** Delete the lease only if it is still ours (it may have expired and been re-claimed). */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);

    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final OtpStore otps;
    private final NotificationSender sender;
    private final CodeCipher cipher;

    public EmailWorker(ObjectMapper mapper, StringRedisTemplate redis, OtpStore otps, NotificationSender sender,
                       CodeCipher cipher) {
        this.mapper = mapper;
        this.redis = redis;
        this.otps = otps;
        this.sender = sender;
        this.cipher = cipher;
    }

    @KafkaListener(topics = Topics.EMAILS, groupId = "email-worker")
    public void onEmailJob(String payload) throws JsonProcessingException {
        EmailJob job = mapper.readValue(payload, EmailJob.class);
        OtpStore.Purpose purpose = job.isVerificationCode() ? OtpStore.Purpose.VERIFY_EMAIL
                : job.isPasswordResetCode() ? OtpStore.Purpose.RESET_PASSWORD : null;
        if (purpose != null && !job.jobId().equals(otps.currentOtpId(purpose, job.to()))) {
            log.debug("Skipping stale code email {} (replaced, used or expired)", job.jobId());
            return;
        }

        // Codes arrive encrypted (D57). A bad ciphertext throws IllegalArgumentException: not retried, dead-lettered.
        String code = purpose == null ? null : cipher.decrypt(job.encryptedCode(), job.jobId(), job.to());

        String stateKey = "email:job:" + job.jobId();
        String lease = "sending:" + UUID.randomUUID();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(stateKey, lease, LEASE))) {
            if (SENT.equals(redis.opsForValue().get(stateKey))) {
                log.info("Duplicate delivery of email job {} ignored (already sent)", job.jobId());
                return;
            }
            throw new EmailInFlightException(job.jobId());
        }

        try {
            sender.send(job.to(), subject(job, code), body(job, code), "<" + job.jobId() + "@wanderly.local>");
        } catch (RuntimeException e) {
            redis.execute(RELEASE, List.of(stateKey), lease);
            throw e;   // retried by the Kafka error handler, then dead-lettered
        }
        redis.opsForValue().set(stateKey, SENT, DONE_TTL);
    }

    private static String subject(EmailJob job, String code) {
        if (job.isVerificationCode()) {
            return code + " is your Wanderly verification code";
        }
        if (job.isPasswordResetCode()) {
            return code + " is your Wanderly password reset code";
        }
        return "You already have a Wanderly account";
    }

    private static String body(EmailJob job, String code) {
        if (job.isVerificationCode()) {
            return """
                    Hi %s,

                    Your Wanderly verification code is:

                        %s

                    Enter it on the sign-up screen to activate your account. It expires in 10 minutes
                    and can only be used once.

                    If you didn't create a Wanderly account, you can ignore this email.
                    """.formatted(job.name(), code);
        }
        if (job.isPasswordResetCode()) {
            return """
                    Hi %s,

                    Someone asked to reset your Wanderly password. Your reset code is:

                        %s

                    Enter it on the "Forgot password" screen together with your new password. It expires
                    in 10 minutes and can only be used once. Resetting signs you out everywhere else.

                    If you didn't ask for this, you can ignore this email. Your password is unchanged.
                    """.formatted(job.name(), code);
        }
        return """
                Hi %s,

                Someone just tried to create a Wanderly account with this email address, but you
                already have one. If that was you, simply log in instead.

                If it wasn't you, you can ignore this email. Your account is unchanged.
                """.formatted(job.name());
    }
}
