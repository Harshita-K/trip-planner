package com.wanderly.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String name) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /**
     * Sign-up result: no token yet. Deliberately the same for new, pending and already-registered
     * emails, so it reveals nothing about which addresses have accounts.
     */
    public record RegisterResponse(String email, boolean verificationRequired, String message) {

        static RegisterResponse checkYourEmail(String email) {
            return new RegisterResponse(email, true,
                    "Check your inbox. If this address can be used, we've sent it a 6-digit code.");
        }
    }

    public record VerifyRequest(@Email @NotBlank String email, @NotBlank @Pattern(regexp = "\\s*\\d{6}\\s*") String code) {
    }

    public record ResendRequest(@Email @NotBlank String email) {
    }

    public record ForgotPasswordRequest(@Email @NotBlank String email) {
    }

    public record ResetPasswordRequest(
            @Email @NotBlank String email,
            @NotBlank @Pattern(regexp = "\\s*\\d{6}\\s*") String code,
            @NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, UUID userId) {
    }

    public record PreferencesRequest(
            @Size(max = 20) List<@NotBlank String> interests,
            @Pattern(regexp = "low|mid|high") String budgetLevel,
            @Pattern(regexp = "relaxed|balanced|packed") String travelPace) {
    }

    public record ProfileResponse(UUID id, String email, String name, Instant createdAt,
                                  List<String> interests, String budgetLevel, TravelPace travelPace) {
    }
}
