package com.wanderly.messaging;

import com.wanderly.config.WanderlyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts one-time codes for their trip through the {@code emails} topic (D57), so anyone who can read
 * Kafka (Kafka UI, a misconfigured consumer, a disk snapshot) sees ciphertext, not a usable code.
 *
 * <p>AES-256-GCM, a fresh 12-byte nonce per message, output {@code base64(nonce || ciphertext+tag)}.
 * The job id and recipient are the associated data, so a ciphertext can't be replayed in another job
 * or to another address. The key is derived from {@code OTP_SECRET} with HMAC-SHA256 under its own
 * label, so it's independent of the key that hashes codes in Redis and needs no extra configuration.
 */
@Component
public class CodeCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_LABEL = "wanderly/email-job-code/aes-256-gcm/v1";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    @Autowired
    public CodeCipher(WanderlyProperties props) {
        this(props.security().otpSecret());
    }

    CodeCipher(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal(KEY_LABEL.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive the email-code key", e);
        }
    }

    public String encrypt(String code, String jobId, String recipient) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, jobId, recipient);
            byte[] sealed = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt code", e);
        }
    }

    /** @throws IllegalArgumentException if the value was tampered with, or belongs to another job or recipient. */
    public String decrypt(String encrypted, String jobId, String recipient) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalArgumentException("Email job has no encrypted code");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            if (all.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("Encrypted code is too short");
            }
            byte[] nonce = java.util.Arrays.copyOf(all, NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce, jobId, recipient);
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Encrypted code failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt code", e);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String jobId, String recipient) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");   // not thread-safe; cheap to create
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((jobId + "\n" + recipient).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
