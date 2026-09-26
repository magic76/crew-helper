package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Android Keystore-backed secret store.
 *
 * Only encrypted ciphertext is persisted in SharedPreferences. The AES key
 * stays inside AndroidKeyStore and is never exposed to app preferences.
 */
final class SecureSecretStore {
    private static final String PREFS = "crew_secure_secrets";
    private static final String KEY_ALIAS = "crew_helper_secrets_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private SecureSecretStore() {}

    static synchronized String read(
            Context context,
            String name) {
        if (context == null || clean(name).isEmpty()) return "";
        try {
            SharedPreferences prefs = prefs(context);
            String encoded = prefs.getString(storageKey(name), "");
            if (encoded == null || encoded.isEmpty()) return "";

            int separator = encoded.indexOf(':');
            if (separator <= 0 || separator >= encoded.length() - 1) {
                return "";
            }

            byte[] iv = Base64.decode(
                    encoded.substring(0, separator),
                    Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(
                    encoded.substring(separator + 1),
                    Base64.NO_WRAP);

            SecretKey key = getExistingKey();
            if (key == null) return "";

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    static synchronized boolean write(
            Context context,
            String name,
            String value) {
        if (context == null || clean(name).isEmpty()) return false;
        String cleanValue = value == null ? "" : value.trim();
        if (cleanValue.isEmpty()) {
            return delete(context, name);
        }

        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return false;

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            byte[] ciphertext = cipher.doFinal(
                    cleanValue.getBytes(StandardCharsets.UTF_8));
            String encoded =
                    Base64.encodeToString(
                            cipher.getIV(),
                            Base64.NO_WRAP)
                            + ":"
                            + Base64.encodeToString(
                                    ciphertext,
                                    Base64.NO_WRAP);

            boolean committed = prefs(context)
                    .edit()
                    .putString(storageKey(name), encoded)
                    .commit();
            if (!committed) return false;

            return cleanValue.equals(read(context, name));
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized boolean delete(
            Context context,
            String name) {
        if (context == null || clean(name).isEmpty()) return false;
        try {
            return prefs(context)
                    .edit()
                    .remove(storageKey(name))
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized boolean containsCiphertext(
            Context context,
            String name) {
        if (context == null || clean(name).isEmpty()) return false;
        try {
            return prefs(context).contains(storageKey(name));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String storageKey(String name) {
        return "secret_" + clean(name);
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key key = store.getKey(KEY_ALIAS, null);
        return key instanceof SecretKey
                ? (SecretKey) key
                : null;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = getExistingKey();
        if (existing != null) return existing;

        KeyGenerator generator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT
                                | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build());
        return generator.generateKey();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
