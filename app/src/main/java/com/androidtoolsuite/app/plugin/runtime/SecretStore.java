package com.androidtoolsuite.app.plugin.runtime;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-backed AES-GCM primitive. Ciphertext is portable only after explicit decrypt/re-encrypt. */
public final class SecretStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "android_tool_suite.runtime_v2.master.v1";
    private static final byte[] MAGIC = new byte[]{'A', 'T', 'S', '2', 'S', 1};
    private static final int IV_BYTES = 12;

    public byte[] encrypt(
            String pluginId,
            String datasetId,
            String key,
            int formatVersion,
            byte[] plaintext
    ) throws CapabilityFailure {
        if (plaintext == null) throw CapabilityFailure.invalid("Secret value is missing");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("Keystore returned invalid GCM IV");
            cipher.updateAAD(aad(pluginId, datasetId, key, formatVersion));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteArrayOutputStream output = new ByteArrayOutputStream(MAGIC.length + 1 + iv.length + ciphertext.length);
            output.write(MAGIC);
            output.write(iv.length);
            output.write(iv);
            output.write(ciphertext);
            return output.toByteArray();
        } catch (Exception error) {
            throw unavailable("Unable to encrypt secret", error);
        }
    }

    public byte[] decrypt(
            String pluginId,
            String datasetId,
            String key,
            int formatVersion,
            byte[] encoded
    ) throws CapabilityFailure {
        if (encoded == null || encoded.length < MAGIC.length + 1 + IV_BYTES + 16) {
            throw new CapabilityFailure("SECRET_UNAVAILABLE", "Secret ciphertext is invalid", false);
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(encoded);
            byte[] magic = new byte[MAGIC.length];
            input.get(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IllegalArgumentException("unknown ciphertext version");
            }
            int ivLength = input.get() & 0xff;
            if (ivLength != IV_BYTES || input.remaining() <= ivLength + 15) {
                throw new IllegalArgumentException("invalid ciphertext header");
            }
            byte[] iv = new byte[ivLength];
            input.get(iv);
            byte[] ciphertext = new byte[input.remaining()];
            input.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(pluginId, datasetId, key, formatVersion));
            return cipher.doFinal(ciphertext);
        } catch (Exception error) {
            throw unavailable("Secret is unavailable or no longer authentic", error);
        }
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] aad(String pluginId, String datasetId, String key, int formatVersion) {
        // The AAD prefix is cryptographic compatibility data, not a source-code version label.
        return ("ats-runtime-v2\n" + clean(pluginId) + "\n" + clean(datasetId) + "\n"
                + clean(key) + "\n" + formatVersion).getBytes(StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static CapabilityFailure unavailable(String message, Throwable error) {
        String detail = error.getMessage();
        return new CapabilityFailure(
                "SECRET_UNAVAILABLE",
                detail == null || detail.trim().isEmpty() ? message : message + ": " + detail,
                false
        );
    }
}
