package com.wanderly.user;

import com.wanderly.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Sign-up is two steps: {@link #register} creates an unverified account and queues a code email;
 * {@link #verify} checks the code and only then issues a token. Login refuses unverified accounts.
 *
 * <p>{@code register} and {@code resendCode} respond identically for new, pending and existing
 * accounts, so they can't be used to find out who has an account.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final UserPreferencesRepository preferences;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final EmailVerificationService verification;
    private final PasswordResetService passwordReset;
    private final TransactionTemplate tx;

    public AuthService(UserRepository users, UserPreferencesRepository preferences, PasswordEncoder passwordEncoder,
                       TokenService tokens, EmailVerificationService verification, PasswordResetService passwordReset,
                       PlatformTransactionManager txManager) {
        this.users = users;
        this.preferences = preferences;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.verification = verification;
        this.passwordReset = passwordReset;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Outcome(User user, boolean alreadyRegistered) {
    }

    public AuthDtos.RegisterResponse register(AuthDtos.RegisterRequest request) {
        String email = normalise(request.email());
        // Hash up front on every path so response time doesn't hint at whether the account exists.
        String hash = passwordEncoder.encode(request.password());
        String name = request.name().trim();

        Outcome outcome = tx.execute(status -> {
            User existing = users.findByEmail(email).orElse(null);
            if (existing != null && existing.isEmailVerified()) {
                return new Outcome(existing, true);
            }
            if (existing != null) {
                existing.replaceUnverifiedCredentials(hash, name);
                return new Outcome(existing, false);
            }
            User created = users.save(new User(email, hash, name));
            preferences.save(new UserPreferences(created.getId()));
            return new Outcome(created, false);
        });

        // After commit: these only write to Redis and enqueue to Kafka; the worker sends the email.
        if (outcome.alreadyRegistered()) {
            verification.notifyExistingAccount(outcome.user());
        } else {
            verification.startVerification(outcome.user());
        }
        return AuthDtos.RegisterResponse.checkYourEmail(email);
    }

    public AuthDtos.TokenResponse verify(AuthDtos.VerifyRequest request) {
        return tokens.issue(verification.verify(normalise(request.email()), request.code()));
    }

    public void resendCode(AuthDtos.ResendRequest request) {
        verification.resend(normalise(request.email()));
    }

    public void forgotPassword(AuthDtos.ForgotPasswordRequest request) {
        passwordReset.requestReset(normalise(request.email()));
    }

    public AuthDtos.TokenResponse resetPassword(AuthDtos.ResetPasswordRequest request) {
        return tokens.issue(passwordReset.reset(normalise(request.email()), request.code(), request.password()));
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        User user = users.findByEmail(normalise(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (!user.isEmailVerified()) {
            // Only reachable with the right password, so it doesn't reveal which emails exist.
            throw new ApiException(HttpStatus.FORBIDDEN, "Please verify your email to finish signing up.",
                    EmailVerificationService.NOT_VERIFIED);
        }
        return tokens.issue(user);
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
