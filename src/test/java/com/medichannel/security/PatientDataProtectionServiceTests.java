package com.medichannel.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatientDataProtectionServiceTests {

    private PatientDataProtectionService protectionService;

    @BeforeEach
    void setUp() {
        String encryptionKey =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String hmacKey =
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

        protectionService = new PatientDataProtectionService(
                encryptionKey,
                hmacKey
        );
    }

    @Test
    void shouldEncryptAndDecryptPatientData() {
        String original = "Ashan Perera";

        String encrypted = protectionService.encrypt(original);
        String decrypted = protectionService.decrypt(encrypted);

        assertNotEquals(original, encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void shouldGenerateDifferentCiphertextForSamePlaintext() {
        String original = "Patient confidential information";

        String first = protectionService.encrypt(original);
        String second = protectionService.encrypt(original);

        assertNotEquals(first, second);
        assertEquals(original, protectionService.decrypt(first));
        assertEquals(original, protectionService.decrypt(second));
    }

    @Test
    void shouldRejectModifiedCiphertext() {
        String encrypted = protectionService.encrypt("Sensitive data");

        String modified = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThrows(
                IllegalStateException.class,
                () -> protectionService.decrypt(modified)
        );
    }

    @Test
    void shouldGenerateSameNicHashForNormalizedValues() {
        String first = protectionService.createNicLookupHash(" 123456789V ");
        String second = protectionService.createNicLookupHash("123456789v");

        assertEquals(first, second);
        assertEquals(43, first.length());
    }

    @Test
    void shouldHandleNullValues() {
        assertNull(protectionService.encrypt(null));
        assertNull(protectionService.decrypt(null));
        assertNull(protectionService.createNicLookupHash(null));
    }
}
