package com.wanderly.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeCipherTest {

    private final CodeCipher cipher = new CodeCipher("local-dev-otp-secret-change-me-32-bytes");

    @Test
    void roundTripsAndNeverRepeatsCiphertext() {
        String a = cipher.encrypt("424242", "job-1", "a@example.com");
        String b = cipher.encrypt("424242", "job-1", "a@example.com");

        assertThat(a).isNotEqualTo(b);   // fresh nonce each time
        assertThat(cipher.decrypt(a, "job-1", "a@example.com")).isEqualTo("424242");
        assertThat(cipher.decrypt(b, "job-1", "a@example.com")).isEqualTo("424242");
    }

    @Test
    void isBoundToItsJobAndRecipient() {
        String sealed = cipher.encrypt("424242", "job-1", "a@example.com");

        assertThatThrownBy(() -> cipher.decrypt(sealed, "job-2", "a@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, "job-1", "b@example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTamperingAndOtherKeys() {
        String sealed = cipher.encrypt("424242", "job-1", "a@example.com");
        byte[] bytes = Base64.getDecoder().decode(sealed);
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "job-1", "a@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeCipher("another-secret-that-is-at-least-32-bytes").decrypt(sealed, "job-1", "a@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, "job-1", "a@example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKafkaMessageDoesNotContainTheCode() throws Exception {
        EmailJob job = EmailJob.verificationCode(cipher, "job-1", "a@example.com", "A", "424242");

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(job);

        assertThat(json).doesNotContain("424242").contains("encryptedCode");
    }
}
