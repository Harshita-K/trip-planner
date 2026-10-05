package com.wanderly.user;

import com.wanderly.common.ApiException;
import com.wanderly.messaging.EmailJob;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.messaging.Topics;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Proves a user owns their email: issue a code (Redis), hand the email to the worker (Kafka),
 * check the code (atomic Lua). Nothing here waits on the mail server.
 */
@Service
public class EmailVerificationService {

    public static final String NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    /** One message for every failure, so responses never reveal whether an account or code exists. */
    static final String INVALID_CODE = "That code is invalid or has expired.";

    private final SecureRandom random = new SecureRandom();
    private final OtpStore otps;
    private final EventPublisher publisher;
    private final UserRepository users;

    public EmailVerificationService(OtpStore otps, EventPublisher publisher, UserRepository users) {
        this.otps = otps;
        this.publisher = publisher;
        this.users = users;
    }

    /**
     * Issue a fresh code and queue its email. Within the 60 s cooldown this is a no-op: the code
     * already sent is still valid, and callers respond identically either way.
     */
    public void startVerification(User user) {
        String code = "%06d".formatted(random.nextInt(1_000_000));   // uniform over 000000-999999
        String otpId = UUID.randomUUID().toString();                   // also the email job's idempotency key
        if (otps.issue(user.getEmail(), code, otpId)) {
            publisher.publish(Topics.EMAILS, user.getEmail(),
                    EmailJob.verificationCode(otpId, user.getEmail(), user.getName(), code));
        }
    }

    /** Someone tried to sign up with an already-registered address: tell the owner instead of sending a code. */
    public void notifyExistingAccount(User user) {
        if (otps.claimCooldown(user.getEmail())) {
            publisher.publish(Topics.EMAILS, user.getEmail(), EmailJob.accountExists(user.getEmail(), user.getName()));
        }
    }

    /**
     * Consumes the code (atomically, single use) and marks the email verified. Wrong, expired,
     * used, burned and unknown-email all produce the same error.
     */
    @Transactional
    public User verify(String email, String code) {
        if (otps.verify(email, code == null ? "" : code.trim()) != OtpStore.VerifyResult.OK) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        User user = users.findByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE));
        user.markEmailVerified();
        return user;
    }

    /** Silently ignores unknown and already-verified emails (no account enumeration). */
    public void resend(String email) {
        users.findByEmail(email).filter(u -> !u.isEmailVerified()).ifPresent(this::startVerification);
    }
}
