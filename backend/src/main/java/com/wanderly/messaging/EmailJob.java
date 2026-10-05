package com.wanderly.messaging;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload on the {@code emails} topic: "send this email". The API publishes and returns; the
 * email worker does the slow SMTP call. {@code jobId} is the idempotency key, and for codes it is
 * also the id of the code in Redis (sign-up and password-reset codes), so a job for a replaced or used code can be recognised as stale.
 *
 * <p>The code travels in plain text, so the topic keeps messages for 1 hour only; a code is useless
 * after 10 minutes or its first use anyway. Encrypting the field is the upgrade path.
 */
public record EmailJob(String jobId, String type, String to, String name, String code, Instant createdAt) {

    public static final String VERIFICATION_CODE = "verification-code";
    public static final String ACCOUNT_EXISTS = "account-exists";
    public static final String PASSWORD_RESET_CODE = "password-reset-code";

    public static EmailJob verificationCode(String otpId, String to, String name, String code) {
        return new EmailJob(otpId, VERIFICATION_CODE, to, name, code, Instant.now());
    }

    public static EmailJob passwordResetCode(String otpId, String to, String name, String code) {
        return new EmailJob(otpId, PASSWORD_RESET_CODE, to, name, code, Instant.now());
    }

    public static EmailJob accountExists(String to, String name) {
        return new EmailJob(UUID.randomUUID().toString(), ACCOUNT_EXISTS, to, name, null, Instant.now());
    }

    @JsonIgnore
    public boolean isVerificationCode() {
        return VERIFICATION_CODE.equals(type);
    }

    @JsonIgnore
    public boolean isPasswordResetCode() {
        return PASSWORD_RESET_CODE.equals(type);
    }
}
