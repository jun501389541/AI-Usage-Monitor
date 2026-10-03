package com.aiusage.monitor.storage;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts credential payloads with a hardware-backed key. Spec §7, §50 item 6.
 *
 * <p>The upstream app wrote the API key into {@code SharedPreferences} as
 * plaintext. This class replaces that: secrets are encrypted with an AES-256-GCM
 * key that lives in the Android Keystore and never leaves it, so a database
 * dump or a backup contains only ciphertext.
 *
 * <p>Each value is stored as {@code base64(iv || ciphertext)}. GCM authenticates
 * the ciphertext, so a tampered row fails to decrypt rather than silently
 * yielding garbage.
 *
 * <h3>Degraded mode</h3>
 *
 * <p>A Keystore key can genuinely be unavailable — a corrupted keystore, a
 * device whose lock screen was removed, an emulator image without a secure
 * element. Failing hard would make the app unusable and would also strand the
 * user's existing key. Instead the store records, per credential, which
 * protection was actually applied ({@link #PROTECTION_KEYSTORE} or
 * {@link #PROTECTION_DEGRADED}), so the condition is visible and reportable
 * rather than silent. Spec §53 rule 25 — do not break the structure for
 * short-term speed, and do not hide a real failure.
 */
public final class SecureStorage {

    /** Payload was encrypted with a Keystore-backed key. */
    public static final String PROTECTION_KEYSTORE = "keystore-aes-gcm";

    /** Keystore was unavailable; payload was obscured with a device-local key. */
    public static final String PROTECTION_DEGRADED = "degraded-local";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "aiusage_credential_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;

    private final Context context;

    public SecureStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Result of an encryption: the stored blob plus how it was protected. */
    public static final class Sealed {

        private final String payload;
        private final String protection;

        Sealed(String payload, String protection) {
            this.payload = payload;
            this.protection = protection;
        }

        /** Base64 of {@code iv || ciphertext}. */
        public String getPayload() {
            return payload;
        }

        public String getProtection() {
            return protection;
        }

        public boolean isDegraded() {
            return PROTECTION_DEGRADED.equals(protection);
        }
    }

    /**
     * Encrypts a payload.
     *
     * @return the sealed blob; never null, falling back to degraded protection
     *         rather than throwing so that a keystore problem cannot make the
     *         app unusable
     */
    public Sealed seal(String plaintext) {
        if (plaintext == null) {
            plaintext = "";
        }
        try {
            return new Sealed(encryptWithKeystore(plaintext), PROTECTION_KEYSTORE);
        } catch (Exception keystoreFailure) {
            return new Sealed(encryptDegraded(plaintext), PROTECTION_DEGRADED);
        }
    }

    /**
     * Decrypts a payload produced by {@link #seal}.
     *
     * @param protection the value stored alongside the payload, so the right
     *                   scheme is used on the way back out
     * @return the plaintext
     * @throws SecureStorageException when the blob cannot be decrypted, which
     *                               normally means the keystore key is gone
     */
    public String open(String payload, String protection) throws SecureStorageException {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        try {
            if (PROTECTION_DEGRADED.equals(protection)) {
                return decryptDegraded(payload);
            }
            return decryptWithKeystore(payload);
        } catch (Exception exception) {
            throw new SecureStorageException(
                    "无法解密凭据（密钥可能已被移除或设备已恢复出厂设置）", exception);
        }
    }

    /** True when a Keystore-backed key is available on this device. */
    public boolean isKeystoreAvailable() {
        try {
            loadOrCreateKey();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    /** Thrown when a stored blob cannot be decrypted. */
    public static class SecureStorageException extends Exception {

        private static final long serialVersionUID = 1L;

        public SecureStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // Keystore-backed path
    // ------------------------------------------------------------------

    private String encryptWithKeystore(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(concat(iv, ciphertext), Base64.NO_WRAP);
    }

    private String decryptWithKeystore(String payload) throws Exception {
        byte[] blob = Base64.decode(payload, Base64.NO_WRAP);
        if (blob.length <= IV_BYTES) {
            throw new SecureStorageException("凭据内容不完整", null);
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, IV_BYTES);
        byte[] ciphertext = new byte[blob.length - IV_BYTES];
        System.arraycopy(blob, IV_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private SecretKey loadOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // No setUserAuthenticationRequired: background widget refreshes must
                // be able to decrypt without the user unlocking a prompt first.
                .build());
        return generator.generateKey();
    }

    // ------------------------------------------------------------------
    // Degraded path
    // ------------------------------------------------------------------

    /**
     * Degraded protection is deliberately NOT a fixed key in the source. It
     * derives from a random per-install value stored in the app's private
     * preferences, so the result is still not a plaintext database column, and
     * the condition is flagged in the credential row so the UI can warn.
     */
    private String encryptDegraded(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            javax.crypto.spec.SecretKeySpec key = degradedKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(concat(iv, ciphertext), Base64.NO_WRAP);
        } catch (Exception exception) {
            // Last resort: store nothing rather than a secret in the clear.
            return "";
        }
    }

    private String decryptDegraded(String payload) throws Exception {
        byte[] blob = Base64.decode(payload, Base64.NO_WRAP);
        if (blob.length <= IV_BYTES) {
            throw new SecureStorageException("凭据内容不完整", null);
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, IV_BYTES);
        byte[] ciphertext = new byte[blob.length - IV_BYTES];
        System.arraycopy(blob, IV_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, degradedKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static final String DEGRADED_PREFS = "aiusage_degraded_key";
    private static final String DEGRADED_KEY = "k";

    private javax.crypto.spec.SecretKeySpec degradedKey() throws Exception {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(DEGRADED_PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(DEGRADED_KEY, null);
        if (stored == null) {
            byte[] material = new byte[32];
            new SecureRandom().nextBytes(material);
            stored = Base64.encodeToString(material, Base64.NO_WRAP);
            preferences.edit().putString(DEGRADED_KEY, stored).apply();
        }
        return new javax.crypto.spec.SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES");
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
