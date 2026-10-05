package com.wanderly.user;

import com.wanderly.common.ApiException;
import com.wanderly.messaging.EmailJob;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.messaging.Topics;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Forgot password, on the sign-up code machinery (D38): a single-use 6-digit code in Redis (its own
 * keys, 10 minutes, 5 attempts, 60 s resend cooldown), emailed by the async worker.
 *
 * <ul>
 *   <li>{@link #requestReset} responds the same whether or not the email has an account, so it can't
 *       be used to find out who has one. Only verified accounts get a code; an unverified sign-up
 *       should just register again.</li>
 *   <li>{@link #reset} consumes the code, sets the new password, signs out every other session
 *       ({@link TokenRevocation}) and logs this one in.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    static final String INVALID_CODE = EmailVerificationService.INVALID_CODE;

    private final SecureRandom random = new SecureRandom();
    private final OtpStore otps;
    private final EventPublisher publisher;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocation revocation;

    public PasswordResetService(OtpStore otps, EventPublisher publisher, UserRepository users,
                                PasswordEncoder passwordEncoder, TokenRevocation revocation) {
        this.otps = otps;
        this.publisher = publisher;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.revocation = revocation;
    }

    /** Silently does nothing for unknown or unverified emails, and within the resend cooldown. */
    public void requestReset(String email) {
        users.findByEmail(email).filter(User::isEmailVerified).ifPresent(user -> {
            String code = "%06d".formatted(random.nextInt(1_000_000));
            String otpId = UUID.randomUUID().toString();
            if (otps.issue(OtpStore.Purpose.RESET_PASSWORD, user.getEmail(), code, otpId)) {
                publisher.publish(Topics.EMAILS, user.getEmail(),
                        EmailJob.passwordResetCode(otpId, user.getEmail(), user.getName(), code));
            }
        });
    }

    /** Wrong, expired, used, burned and unknown-email all produce the same error. */
    @Transactional
    public User reset(String email, String code, String newPassword) {
        // Hash first: it's the slow part, and it shouldn't run after the code is spent.
        String hash = passwordEncoder.encode(newPassword);
        if (otps.verify(OtpStore.Purpose.RESET_PASSWORD, email, code == null ? "" : code.trim()) != OtpStore.VerifyResult.OK) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        User user = users.findByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE));
        user.changePassword(hash);
        revocation.revokeIssuedBefore(user.getId(), Instant.now());
        return user;
    }
}
