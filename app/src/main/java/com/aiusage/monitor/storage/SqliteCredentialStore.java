package com.aiusage.monitor.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.auth.AuthException;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.provider.AuthContext;

import java.util.UUID;

/**
 * SQLite-backed credential storage with Keystore encryption. Spec §7, §45.
 *
 * <p>Two properties matter here and are enforced structurally rather than by
 * convention:
 *
 * <ul>
 *   <li><b>Plaintext never reaches the database.</b> The only column that holds
 *       payload data is {@code encrypted_payload}, and it is written exclusively
 *       from {@link SecureStorage#seal}. Spec §7 forbids plaintext keys in the
 *       database, tokens in {@code SharedPreferences}, and tokens in logs.</li>
 *   <li><b>Credentials are separate rows from accounts.</b> Replacing a key
 *       updates this row only, so the account id, its history and any widget
 *       bound to it are untouched. Spec §14.</li>
 * </ul>
 */
public final class SqliteCredentialStore implements CredentialStore, AccountManager.DegradedAware {

    private final Database database;
    private final SecureStorage secureStorage;

    public SqliteCredentialStore(Context context) {
        this.database = Database.get(context);
        this.secureStorage = new SecureStorage(context);
    }

    @Override
    public String create(AuthType type, String payload) throws AuthException {
        String id = "cred_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long now = System.currentTimeMillis();
        SecureStorage.Sealed sealed = secureStorage.seal(payload);
        requireAllowed(type, sealed);

        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("type", (type == null ? AuthType.CUSTOM : type).name());
        values.put("encrypted_payload", sealed.getPayload());
        values.put("protection", sealed.getProtection());
        values.put("created_at", now);
        values.put("updated_at", now);

        database.getWritableDatabase().insertOrThrow(Database.TABLE_CREDENTIALS, null, values);
        return id;
    }

    @Override
    public void update(String credentialId, String payload) throws AuthException {
        if (credentialId == null || credentialId.isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据 ID 为空");
        }
        SecureStorage.Sealed sealed = secureStorage.seal(payload);
        requireAllowed(typeFor(credentialId), sealed);

        ContentValues values = new ContentValues();
        values.put("encrypted_payload", sealed.getPayload());
        values.put("protection", sealed.getProtection());
        values.put("updated_at", System.currentTimeMillis());

        int updated = database.getWritableDatabase().update(
                Database.TABLE_CREDENTIALS,
                values,
                "id = ?",
                new String[]{credentialId});

        if (updated == 0) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在：" + credentialId);
        }
    }

    @Override
    public AuthContext open(String credentialId, AuthType type) throws AuthException {
        if (credentialId == null || credentialId.isEmpty()) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "账户未绑定凭据");
        }

        String payload = null;
        String protection = null;
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_CREDENTIALS,
                new String[]{"encrypted_payload", "protection"},
                "id = ?",
                new String[]{credentialId},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                payload = cursor.getString(0);
                protection = cursor.getString(1);
            }
        }

        if (payload == null) {
            throw new AuthException(UsageError.INVALID_CREDENTIAL, "凭据不存在");
        }

        String plaintext;
        try {
            plaintext = secureStorage.open(payload, protection);
        } catch (SecureStorage.SecureStorageException exception) {
            throw new AuthException(UsageError.AUTH_EXPIRED, exception.getMessage(), exception);
        }

        AuthType effective = type == null ? AuthType.API_KEY : type;
        switch (effective) {
            case API_KEY:
                return new com.aiusage.monitor.auth.ApiKeyAuthAdapter().adapt(plaintext);
            case BRIDGE_TOKEN:
                // Added in Phase 6: before this, a Codex account could be created
                // in the database but never opened for a fetch - the default branch
                // below answered every non-API_KEY type with UNSUPPORTED.
                return new com.aiusage.monitor.auth.BridgeAuthAdapter().adapt(plaintext);
            case OAUTH:
                return new com.aiusage.monitor.auth.OAuthAuthAdapter().adapt(plaintext);
            default:
                throw new AuthException(
                        UsageError.UNSUPPORTED, "暂不支持该认证方式：" + effective);
        }
    }

    @Override
    public void delete(String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return;
        }
        database.getWritableDatabase().delete(
                Database.TABLE_CREDENTIALS, "id = ?", new String[]{credentialId});
    }

    @Override
    public boolean isUsable(String credentialId) {
        try {
            open(credentialId, typeFor(credentialId));
            return true;
        } catch (AuthException exception) {
            return false;
        }
    }

    /**
     * Reports whether a credential was stored with degraded protection, so the
     * UI can warn that the Keystore was unavailable on this device. Spec §7
     * requires Keystore; a fallback must be visible, not silent.
     */
    @Override
    public boolean isDegraded(String credentialId) {
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_CREDENTIALS,
                new String[]{"protection"},
                "id = ?",
                new String[]{credentialId},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return SecureStorage.PROTECTION_DEGRADED.equals(cursor.getString(0));
            }
        }
        return false;
    }

    private void requireAllowed(AuthType type, SecureStorage.Sealed sealed) throws AuthException {
        try {
            CredentialProtectionPolicy.requireAcceptable(type, sealed.getProtection());
        } catch (IllegalStateException rejected) {
            throw new AuthException(UsageError.AUTH_EXPIRED,
                    "OAuth 令牌无法通过 Android Keystore 安全保存", rejected);
        }
    }

    private AuthType typeFor(String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) return AuthType.CUSTOM;
        try (Cursor cursor = database.getReadableDatabase().query(
                Database.TABLE_CREDENTIALS, new String[]{"type"}, "id = ?",
                new String[]{credentialId}, null, null, null)) {
            if (!cursor.moveToFirst()) return AuthType.CUSTOM;
            try {
                return AuthType.valueOf(cursor.getString(0));
            } catch (RuntimeException invalid) {
                return AuthType.CUSTOM;
            }
        }
    }
}
