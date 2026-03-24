package com.agent.service;

import com.agent.config.AppConfig;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption / decryption utility for sensitive token fields.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   String encrypted = TokenEncryptionService.encrypt(plainText);
 *   String decrypted = TokenEncryptionService.decrypt(encrypted);
 * }</pre>
 *
 * <h3>Storage format</h3>
 * {@code Base64( IV[12 bytes] || CipherText || AuthTag[16 bytes] )}
 *
 * <p>The 12-byte IV is randomly generated per call, so encrypting the same
 * plain-text twice produces different ciphertext — this is intentional and
 * prevents leaking equality information in the database.
 *
 * <h3>Key derivation</h3>
 * The AES-256 key is derived by computing SHA-256 over the
 * {@code ENCRYPTION_SECRET} environment variable (via {@link AppConfig}).
 * <strong>Always override {@code ENCRYPTION_SECRET} in production.</strong>
 *
 * <h3>Encrypted columns</h3>
 * <ul>
 *   <li>{@code auth_tokens.access_token}</li>
 *   <li>{@code auth_tokens.refresh_token}</li>
 *   <li>{@code auth_tokens.client_secret}</li>
 * </ul>
 */
public final class TokenEncryptionService {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH  = 12;   // 96-bit IV (NIST recommended)
    private static final int    GCM_TAG_LENGTH = 128;  // 128-bit authentication tag

    // ── Key (derived once at class-load time) ─────────────────────────────────

    private static final SecretKey SECRET_KEY = deriveKey(AppConfig.ENCRYPTION_SECRET);

    // ── Private constructor — static utility class ────────────────────────────

    private TokenEncryptionService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts {@code plainText} using AES-256-GCM with a freshly generated IV.
     *
     * @param plainText the value to encrypt; returns {@code null} if input is {@code null}
     * @return Base64-encoded string: {@code Base64(IV || CipherText+AuthTag)}
     * @throws RuntimeException if encryption fails (algorithm not available, etc.)
     */
    public static String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            // Generate a fresh random 96-bit IV for every encryption call
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, spec);

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext+authTag so we can recover it during decrypt
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv,          0, combined, 0,          iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length,  cipherBytes.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            throw new RuntimeException("TokenEncryptionService: encryption failed", e);
        }
    }

    /**
     * Decrypts a value that was previously encrypted by {@link #encrypt(String)}.
     *
     * @param encryptedText Base64-encoded ciphertext; returns {@code null} if input is {@code null}
     * @return the original plain-text string
     * @throws RuntimeException if decryption fails (wrong key, tampered data, etc.)
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            // Split IV and ciphertext
            byte[] iv         = Arrays.copyOfRange(combined, 0,             GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, spec);

            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("TokenEncryptionService: decryption failed", e);
        }
    }

    // ── Key derivation ────────────────────────────────────────────────────────

    /**
     * Derives a 256-bit AES key from the given secret by computing its
     * SHA-256 digest.
     *
     * @param secret the raw secret string (from {@link AppConfig#ENCRYPTION_SECRET})
     * @return a {@link SecretKey} suitable for AES-256
     */
    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes   = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("TokenEncryptionService: key derivation failed", e);
        }
    }
}
