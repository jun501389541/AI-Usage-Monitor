package com.aiusage.monitor.provider;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageResult;

import java.util.List;

/**
 * A provider is one platform: DeepSeek, Codex, OpenRouter, Claude, Gemini and
 * so on. Spec §4.
 *
 * <p>A provider knows how to talk to its platform and how to turn that
 * platform's raw response into a {@link UsageResult}. It knows nothing else.
 * Specifically, per spec §4 and §53, a provider must not:
 *
 * <ul>
 *   <li>render UI or touch widgets (rule 10)</li>
 *   <li>read or write the database, SharedPreferences, or the credential store</li>
 *   <li>persist or refresh tokens (§8)</li>
 *   <li>depend on another provider (rule 13)</li>
 * </ul>
 *
 * <p>Adding a platform should mean writing one implementation and registering it
 * — never editing the registry, the UI, or the widget. Spec §5.
 */
public interface UsageProvider {

    /** Stable identifier used in {@code Account.providerId}, for example {@code deepseek}. */
    String getId();

    /** Human-readable platform name, for example "DeepSeek". */
    String getName();

    /** Authentication mechanisms this provider accepts. */
    List<AuthType> getSupportedAuthTypes();

    /** Declared abilities, read by the UI instead of hard-coded assumptions. */
    ProviderCapabilities getCapabilities();

    /**
     * Fetches current usage for one account.
     *
     * @param account     the account being refreshed; carries identity, never secrets
     * @param authContext the decrypted secret for this fetch only
     * @return a normalised result; never null
     * @throws UsageException when the fetch fails, carrying a normalised
     *                        {@link com.aiusage.monitor.model.UsageError} so that
     *                        raw platform errors never reach the widget (§49)
     */
    UsageResult fetchUsage(Account account, AuthContext authContext) throws UsageException;
}
