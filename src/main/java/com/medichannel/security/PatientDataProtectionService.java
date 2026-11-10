package com.medichannel.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PatientDataProtectionService {

    private static final String ENCRYPTION_PREFIX = "v1:";
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey encryptionKey;
    private final SecretKey hmacKey;
    private final java.security.SecureRandom secureRandom =
            new java.security.SecureRandom();

    public PatientDataProtectionService(
            @Value("${ENCRYPTION_KEY}") String encodedEncryptionKey,
            @Value("${HMAC_SECRET}") String encodedHmacKey
    ) {
        this.encryptionKey = createKey(encodedEncryptionKey, "AES");
        this.hmacKey = createKey(encodedHmacKey, HMAC_ALGORITHM);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            byte[] encrypted = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8)
            );

            ByteBuffer buffer = ByteBuffer.allocate(
                    iv.length + encrypted.length
            );
            buffer.put(iv);
            buffer.put(encrypted);

            return ENCRYPTION_PREFIX
                    + Base64.getEncoder().encodeToString(buffer.array());

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to protect patient information."
            );
        }
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null) {
            return null;
        }

        if (!encryptedValue.startsWith(ENCRYPTION_PREFIX)) {
            throw new IllegalArgumentException(
                    "Unsupported encrypted data version."
            );
        }

        try {
            byte[] combined = Base64.getDecoder().decode(
                    encryptedValue.substring(ENCRYPTION_PREFIX.length())
            );

            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException(
                        "Invalid encrypted data."
                );
            }

            ByteBuffer buffer = ByteBuffer.wrap(combined);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv)
            );

            return new String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8
            );

        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Unable to read protected patient information."
            );
        }
    }

    public String createNicLookupHash(String nic) {
        if (nic == null || nic.isBlank()) {
            return null;
        }

        try {
            String normalizedNic = nic
                    .trim()
                    .replaceAll("\\s+", "")
                    .toUpperCase(java.util.Locale.ROOT);

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);

            byte[] hash = mac.doFinal(
                    normalizedNic.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to process patient identifier."
            );
        }
    }

    private SecretKey createKey(String encodedKey, String algorithm) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);

            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                        "Configured security keys must contain exactly 32 bytes."
                );
            }

            return new SecretKeySpec(keyBytes, algorithm);

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Configured security key must be valid Base64.",
                    exception
            );
        }
    }
}
