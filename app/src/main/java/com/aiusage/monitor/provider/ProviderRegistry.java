package com.aiusage.monitor.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that knows which providers exist. Spec §5.
 *
 * <p>Callers resolve a provider by id instead of branching on a constant. The
 * spec explicitly forbids the alternative:
 *
 * <pre>
 * // forbidden by spec §5
 * if (provider == DEEPSEEK) { … } else if (provider == CODEX) { … }
 * </pre>
 *
 * <p>Consequence: adding a platform requires creating a provider and registering
 * it here, with no edit to the UI, the account manager, the refresh manager or
 * the widget. Spec §5 / §53 rule 12.
 */
public final class ProviderRegistry {

    private static final ProviderRegistry INSTANCE = new ProviderRegistry();

    private final Map<String, UsageProvider> providers = new LinkedHashMap<>();

    private ProviderRegistry() {
    }

    /** Process-wide registry. */
    public static ProviderRegistry get() {
        return INSTANCE;
    }

    /**
     * Registers a provider under its own id.
     *
     * @throws IllegalArgumentException if another provider already claims that id,
     *                                  which would make resolution ambiguous
     */
    public synchronized void register(UsageProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        String id = provider.getId();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("provider id must not be empty");
        }
        UsageProvider existing = providers.get(id);
        if (existing != null && existing != provider) {
            throw new IllegalArgumentException("provider id already registered: " + id);
        }
        providers.put(id, provider);
    }

    /** Returns the provider for an id, or null when unknown. */
    public synchronized UsageProvider find(String providerId) {
        if (providerId == null) {
            return null;
        }
        return providers.get(providerId);
    }

    /**
     * Returns the provider for an id, or throws. Use when an unknown provider id
     * means the data is corrupt rather than merely unavailable.
     */
    public synchronized UsageProvider require(String providerId) throws UsageException {
        UsageProvider provider = find(providerId);
        if (provider == null) {
            throw new UsageException(
                    com.aiusage.monitor.model.UsageError.UNSUPPORTED,
                    "unknown provider: " + providerId);
        }
        return provider;
    }

    /** All registered providers, in registration order. */
    public synchronized List<UsageProvider> all() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    public synchronized boolean isEmpty() {
        return providers.isEmpty();
    }

    /**
     * Registers the providers this build ships with. Called once at application
     * start; keeping it in one method means a new platform is a one-line change
     * in a single file.
     */
    public synchronized void registerBuiltIns() {
        register(new com.aiusage.monitor.provider.deepseek.DeepSeekProvider());
        // Phase 6: Codex is reached through the Windows Bridge. Adding a platform
        // is meant to be a change here and nowhere else (Spec §5).
        register(new com.aiusage.monitor.provider.codex.CodexProvider());
    }
}
