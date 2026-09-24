package org.schabi.newpipe.youtube;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class YouTubeCredentialStore {
    public static final String COOKIE_KEY = "youtube_cookies_key";
    public static final String PO_TOKEN_KEY = "youtube_po_token_key";

    private static final String SECURE_PREFS = "youtube_credentials_secure";
    private static final String SECURE_COOKIE_KEY = "cookie";
    private static final String SECURE_PO_TOKEN_KEY = "po_token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "altertube_youtube_credentials_v1";
    private static final String ENCRYPTED_PREFIX = "v1:";
    private static final byte[] AAD = "altertube.youtube.credentials.v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final Object LOCK = new Object();

    private YouTubeCredentialStore() {
    }

    public static void prepareForStartup(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences securePreferences = appContext.getSharedPreferences(
                SECURE_PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            restoreIfPresent(preferences, securePreferences, COOKIE_KEY, SECURE_COOKIE_KEY);
            restoreIfPresent(preferences, securePreferences, PO_TOKEN_KEY, SECURE_PO_TOKEN_KEY);
            migrateIfNeeded(preferences, securePreferences, COOKIE_KEY, SECURE_COOKIE_KEY);
            migrateIfNeeded(preferences, securePreferences, PO_TOKEN_KEY, SECURE_PO_TOKEN_KEY);
        }
    }

    public static void restoreAfterSettingsMigration(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences securePreferences = appContext.getSharedPreferences(
                SECURE_PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            restoreIfPresent(preferences, securePreferences, COOKIE_KEY, SECURE_COOKIE_KEY);
            restoreIfPresent(preferences, securePreferences, PO_TOKEN_KEY, SECURE_PO_TOKEN_KEY);
        }
    }

    public static String getCookies(Context context) {
        return getCredential(context, COOKIE_KEY, SECURE_COOKIE_KEY);
    }

    public static String getPoToken(Context context) {
        return getCredential(context, PO_TOKEN_KEY, SECURE_PO_TOKEN_KEY);
    }

    public static boolean hasCredentials(Context context) {
        return hasSessionCookie(getCookies(context));
    }

    public static boolean hasSessionCookie(String cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return false;
        }
        boolean hasSapisid = false;
        boolean hasPsid = false;
        String[] parts = cookies.split(";");
        for (String part : parts) {
            String cookie = part.trim();
            int separator = cookie.indexOf('=');
            if (separator <= 0 || cookie.length() <= separator + 1) {
                continue;
            }
            String name = cookie.substring(0, separator).trim();
            if ("SAPISID".equals(name)
                    || "__Secure-1PAPISID".equals(name)
                    || "__Secure-3PAPISID".equals(name)) {
                hasSapisid = true;
            } else if ("__Secure-1PSID".equals(name)
                    || "__Secure-3PSID".equals(name)) {
                hasPsid = true;
            }
        }
        return hasSapisid && hasPsid;
    }

    public static void saveCredentials(Context context, String cookies, String poToken)
            throws IllegalStateException {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences securePreferences = appContext.getSharedPreferences(
                SECURE_PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            if (!hasSessionCookie(cookies)) {
                throw new IllegalStateException("YouTube session cookies are required");
            }
            String encryptedCookies = encryptOrNull(cookies);
            String encryptedPoToken = encryptOrNull(poToken);
            if (encryptedCookies == null
                    || (hasText(poToken) && encryptedPoToken == null)) {
                throw new IllegalStateException("Credential encryption unavailable");
            }
            Map<String, String> previousDefaultValues = snapshotCredentialValues(
                    preferences, COOKIE_KEY, PO_TOKEN_KEY);
            Map<String, String> previousSecureValues = snapshotCredentialValues(
                    securePreferences, SECURE_COOKIE_KEY, SECURE_PO_TOKEN_KEY);
            SharedPreferences.Editor defaultEditor = preferences.edit()
                    .putString(COOKIE_KEY, encryptedCookies);
            SharedPreferences.Editor secureEditor = securePreferences.edit()
                    .putString(SECURE_COOKIE_KEY, encryptedCookies);
            if (hasText(poToken)) {
                defaultEditor.putString(PO_TOKEN_KEY, encryptedPoToken);
                secureEditor.putString(SECURE_PO_TOKEN_KEY, encryptedPoToken);
            } else {
                defaultEditor.remove(PO_TOKEN_KEY);
                secureEditor.remove(SECURE_PO_TOKEN_KEY);
            }
            boolean secureCommitted = secureEditor.commit();
            boolean defaultCommitted = secureCommitted && defaultEditor.commit();
            if (!defaultCommitted || !secureCommitted) {
                boolean defaultRestored = restoreCredentialValues(
                        preferences, previousDefaultValues, COOKIE_KEY, PO_TOKEN_KEY);
                boolean secureRestored = restoreCredentialValues(
                        securePreferences, previousSecureValues,
                        SECURE_COOKIE_KEY, SECURE_PO_TOKEN_KEY);
                if (!defaultRestored || !secureRestored) {
                    disableCredentialState(preferences, securePreferences);
                }
                throw new IllegalStateException("Credential state could not be stored");
            }
        }
    }

    public static void clearCredentials(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences securePreferences = appContext.getSharedPreferences(
                SECURE_PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            boolean secureCommitted = securePreferences.edit()
                    .remove(SECURE_COOKIE_KEY)
                    .remove(SECURE_PO_TOKEN_KEY)
                    .commit();
            boolean defaultCommitted = preferences.edit()
                    .remove(COOKIE_KEY)
                    .remove(PO_TOKEN_KEY)
                    .commit();
            deleteKey();
            if (!defaultCommitted || !secureCommitted) {
                disableCredentialState(preferences, securePreferences);
            }
        }
    }

    public static boolean isCredentialKey(String key) {
        return COOKIE_KEY.equals(key)
                || PO_TOKEN_KEY.equals(key)
                || "recaptcha_cookies_key".equals(key)
                || "proxy_token_key".equals(key)
                || "youtube_cookies_key_encrypted".equals(key)
                || "youtube_po_token_key_encrypted".equals(key)
                || key.startsWith("youtube_credentials_secure");
    }

    public static boolean isEncryptedValue(String value) {
        if (value == null || !value.startsWith(ENCRYPTED_PREFIX)) {
            return false;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            return false;
        }
        try {
            Base64.decode(parts[1], Base64.NO_WRAP);
            Base64.decode(parts[2], Base64.NO_WRAP);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static Context wrapContext(Context context) {
        return new CredentialContextWrapper(context.getApplicationContext());
    }

    private static String getCredential(Context context, String preferenceKey,
                                         String secureKey) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences securePreferences = appContext.getSharedPreferences(
                SECURE_PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            return readCredential(preferences, securePreferences, preferenceKey, secureKey);
        }
    }

    private static String readCredential(SharedPreferences preferences,
                                         SharedPreferences securePreferences,
                                         String preferenceKey, String secureKey) {
        String value = preferences.getString(preferenceKey, null);
        if (value == null) {
            value = securePreferences.getString(secureKey, null);
        }
        if (value == null) {
            return null;
        }
        if (isEncryptedValue(value)) {
            final String decrypted = decryptOrNull(value);
            if (decrypted != null) {
                return decrypted;
            }
            value = securePreferences.getString(secureKey, null);
            if (value == null) {
                return null;
            }
            return decryptOrNull(value);
        }
        String encrypted = encryptOrNull(value);
        if (encrypted != null) {
            SharedPreferences.Editor defaultEditor = preferences.edit();
            SharedPreferences.Editor secureEditor = securePreferences.edit();
            defaultEditor.putString(preferenceKey, encrypted);
            secureEditor.putString(secureKey, encrypted);
            defaultEditor.commit();
            secureEditor.commit();
        }
        return value;
    }

    private static void restoreIfPresent(SharedPreferences preferences,
                                        SharedPreferences securePreferences,
                                        String preferenceKey, String secureKey) {
        if (preferences.contains(preferenceKey)) {
            return;
        }
        String value = securePreferences.getString(secureKey, null);
        if (value == null || !isEncryptedValue(value)) {
            return;
        }
        preferences.edit().putString(preferenceKey, value).commit();
    }

    private static void migrateIfNeeded(SharedPreferences preferences,
                                        SharedPreferences securePreferences,
                                        String preferenceKey, String secureKey) {
        if (!preferences.contains(preferenceKey)) {
            return;
        }
        String value = preferences.getString(preferenceKey, null);
        if (value == null || value.isEmpty()) {
            securePreferences.edit().remove(secureKey).commit();
            return;
        }
        String encrypted = isEncryptedValue(value) ? value : encryptOrNull(value);
        if (encrypted == null) {
            return;
        }
        preferences.edit().putString(preferenceKey, encrypted).commit();
        securePreferences.edit().putString(secureKey, encrypted).commit();
    }

    private static String encryptOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ENCRYPTED_PREFIX
                    + encode(cipher.getIV()) + ":" + encode(encrypted);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decryptOrNull(String value) {
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !ENCRYPTED_PREFIX.equals(parts[0])) {
                return null;
            }
            byte[] iv = decode(parts[1]);
            byte[] encrypted = decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static Map<String, String> snapshotCredentialValues(
            SharedPreferences preferences, String... keys) {
        Map<String, String> values = new HashMap<>();
        Map<String, ?> allValues = preferences.getAll();
        for (String key : keys) {
            Object value = allValues.get(key);
            if (value instanceof String) {
                values.put(key, (String) value);
            }
        }
        return values;
    }

    private static boolean restoreCredentialValues(SharedPreferences preferences,
                                                    Map<String, String> values,
                                                    String... keys) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : keys) {
            String value = values.get(key);
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
        }
        return editor.commit();
    }

    private static void disableCredentialState(SharedPreferences preferences,
                                               SharedPreferences securePreferences) {
        preferences.edit()
                .remove(COOKIE_KEY)
                .remove(PO_TOKEN_KEY)
                .commit();
        securePreferences.edit()
                .remove(SECURE_COOKIE_KEY)
                .remove(SECURE_PO_TOKEN_KEY)
                .commit();
        deleteKey();
    }

    private static String secureKeyFor(String preferenceKey) {
        if (COOKIE_KEY.equals(preferenceKey)) {
            return SECURE_COOKIE_KEY;
        }
        if (PO_TOKEN_KEY.equals(preferenceKey)) {
            return SECURE_PO_TOKEN_KEY;
        }
        return null;
    }

    private static final class CredentialContextWrapper extends ContextWrapper {
        private CredentialContextWrapper(Context base) {
            super(base);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            SharedPreferences delegate = super.getSharedPreferences(name, mode);
            if (name == null || !name.equals(getPackageName() + "_preferences")) {
                return delegate;
            }
            return new CredentialPreferences(delegate, getApplicationContext());
        }
    }

    private static final class CredentialPreferences implements SharedPreferences {
        private final SharedPreferences delegate;
        private final Context context;

        private CredentialPreferences(SharedPreferences delegate, Context context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public Map<String, ?> getAll() {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, ?> entry : delegate.getAll().entrySet()) {
                if (secureKeyFor(entry.getKey()) != null
                        && entry.getValue() instanceof String) {
                    result.put(entry.getKey(), readCredential(
                            delegate,
                            context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE),
                            entry.getKey(), secureKeyFor(entry.getKey())));
                } else {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        @Override
        public String getString(String key, String defValue) {
            if (isCredentialKey(key) && secureKeyFor(key) != null) {
                String value = readCredential(
                        delegate,
                        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE),
                        key, secureKeyFor(key));
                return value == null ? defValue : value;
            }
            return delegate.getString(key, defValue);
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return delegate.getStringSet(key, defValues);
        }

        @Override
        public int getInt(String key, int defValue) {
            return delegate.getInt(key, defValue);
        }

        @Override
        public long getLong(String key, long defValue) {
            return delegate.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            return delegate.getFloat(key, defValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return delegate.getBoolean(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            if (isCredentialKey(key) && secureKeyFor(key) != null) {
                return delegate.contains(key)
                        || context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
                        .contains(secureKeyFor(key));
            }
            return delegate.contains(key);
        }

        @Override
        public SharedPreferences.Editor edit() {
            return new CredentialEditor(delegate.edit(), context);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener listener) {
            delegate.registerOnSharedPreferenceChangeListener(listener);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener listener) {
            delegate.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    private static final class CredentialEditor implements SharedPreferences.Editor {
        private final SharedPreferences.Editor delegate;
        private final Context context;
        private final Map<String, String> encryptedPuts = new LinkedHashMap<>();

        private CredentialEditor(SharedPreferences.Editor delegate, Context context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            if (isCredentialKey(key) && secureKeyFor(key) != null) {
                String encrypted = encryptOrNull(value);
                if (encrypted == null) {
                    delegate.remove(key);
                } else {
                    encryptedPuts.put(key, encrypted);
                }
                return this;
            }
            return delegate.putString(key, value);
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            if (isCredentialKey(key)) {
                return this;
            }
            return delegate.putStringSet(key, values);
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            if (isCredentialKey(key)) {
                return this;
            }
            return delegate.putInt(key, value);
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            if (isCredentialKey(key)) {
                return this;
            }
            return delegate.putLong(key, value);
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            if (isCredentialKey(key)) {
                return this;
            }
            return delegate.putFloat(key, value);
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            if (isCredentialKey(key)) {
                return this;
            }
            return delegate.putBoolean(key, value);
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            if (isCredentialKey(key) && secureKeyFor(key) != null) {
                encryptedPuts.remove(key);
                context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
                        .edit().remove(secureKeyFor(key)).apply();
            }
            return delegate.remove(key);
        }

        @Override
        public SharedPreferences.Editor clear() {
            encryptedPuts.clear();
            context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply();
            return delegate.clear();
        }

        @Override
        public boolean commit() {
            for (Map.Entry<String, String> entry : encryptedPuts.entrySet()) {
                delegate.putString(entry.getKey(), entry.getValue());
            }
            boolean committed = delegate.commit();
            if (committed) {
                SharedPreferences.Editor secureEditor = context.getSharedPreferences(
                        SECURE_PREFS, Context.MODE_PRIVATE).edit();
                for (Map.Entry<String, String> entry : encryptedPuts.entrySet()) {
                    String secureKey = secureKeyFor(entry.getKey());
                    if (secureKey != null) {
                        secureEditor.putString(secureKey, entry.getValue());
                    }
                }
                secureEditor.apply();
            }
            encryptedPuts.clear();
            return committed;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
