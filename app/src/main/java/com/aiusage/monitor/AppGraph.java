package com.aiusage.monitor;

import android.content.Context;

import com.aiusage.monitor.account.AccountManager;
import com.aiusage.monitor.account.AccountRepository;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.bridge.BridgeRepository;
import com.aiusage.monitor.provider.ProviderRegistry;
import com.aiusage.monitor.refresh.AccountRefreshManager;
import com.aiusage.monitor.storage.AppSettings;
import com.aiusage.monitor.storage.Database;
import com.aiusage.monitor.storage.LegacyMigration;
import com.aiusage.monitor.storage.SqliteAccountRepository;
import com.aiusage.monitor.storage.SqliteBridgeRepository;
import com.aiusage.monitor.storage.SqliteCredentialStore;
import com.aiusage.monitor.storage.SqliteUsageRepository;
import com.aiusage.monitor.storage.SqliteWidgetConfigStore;
import com.aiusage.monitor.usage.UsageRepository;
import com.aiusage.monitor.widget.WidgetConfigStore;

/**
 * The composition root: the one place that decides which implementation backs
 * each interface.
 *
 * <p>Both entry points — the app UI and the background widget path — build their
 * object graph here. That is what makes the spec's single-refresh-chain rule
 * (§37) structural rather than aspirational: there is exactly one
 * {@link AccountRefreshManager}, one {@link UsageRepository} and one credential
 * store in the process, so the two paths cannot end up with different data.
 *
 * <p>It also keeps the Android dependency out of the layers below: nothing in
 * {@code model}, {@code provider}, {@code auth}, {@code account},
 * {@code usage} or {@code refresh} knows about a {@code Context}, and this class
 * is where the context stops.
 *
 * <p>Instances are held per application context and are safe to share between a
 * broadcast receiver and an activity in the same process. The database helper
 * underneath is itself a process-wide singleton, so a second {@code AppGraph}
 * would still share storage; this just avoids rebuilding the small wrappers.
 */
public final class AppGraph {

    private static volatile AppGraph instance;

    private final Context appContext;
    private final AccountManager accountManager;
    private final UsageRepository usageRepository;
    private final AccountRefreshManager refreshManager;
    private final WidgetConfigStore widgetConfigStore;
    private final BridgeRepository bridgeRepository;
    private final AppSettings settings;
    private final LegacyMigration legacyMigration;

    private AppGraph(Context context) {
        this.appContext = context.getApplicationContext();

        ProviderRegistry registry = ProviderRegistry.get();
        // registerBuiltIns() is not idempotent — it constructs a fresh provider
        // each call and the registry rejects a second instance under the same id
        // — so it is guarded rather than trusted. Without this, a second
        // AppGraph (a process restart after a configuration change, or a test
        // that built one already) would crash on start.
        if (registry.find(com.aiusage.monitor.provider.deepseek.DeepSeekProvider.ID) == null) {
            registry.registerBuiltIns();
        }

        AccountRepository accounts = new SqliteAccountRepository(appContext);
        CredentialStore credentials = new SqliteCredentialStore(appContext);
        this.accountManager = new AccountManager(accounts, credentials);
        this.usageRepository = new SqliteUsageRepository(appContext);
        this.bridgeRepository = new SqliteBridgeRepository(appContext);
        // The bridge repository goes into the refresh path because a paired account
        // is read *through* its computer's row: the address a laptop has now, and the
        // digest its certificate must match, are both facts about the computer rather
        // than about the account (docs/PHASE-7-PLAN.md A5/A9). Built before
        // refreshManager for that reason.
        this.refreshManager = new AccountRefreshManager(
                accountManager, usageRepository, registry, bridgeRepository);
        this.widgetConfigStore = new SqliteWidgetConfigStore(appContext);
        this.settings = new AppSettings(appContext);
        this.legacyMigration = new LegacyMigration(appContext, accountManager, Database.get(appContext));
    }

    public static AppGraph get(Context context) {
        AppGraph local = instance;
        if (local == null) {
            synchronized (AppGraph.class) {
                local = instance;
                if (local == null) {
                    local = new AppGraph(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public Context appContext() {
        return appContext;
    }

    public AccountManager accountManager() {
        return accountManager;
    }

    public UsageRepository usageRepository() {
        return usageRepository;
    }

    public AccountRefreshManager refreshManager() {
        return refreshManager;
    }

    public WidgetConfigStore widgetConfigStore() {
        return widgetConfigStore;
    }

    /**
     * The paired computers: what the reader resolves an account through, and what
     * {@link #pairingStore()} writes. Phase 7 steps 5 and 9.
     */
    public BridgeRepository bridgeRepository() {
        return bridgeRepository;
    }

    /**
     * Records a finished pairing: the computer's row plus the account that reads
     * through it. Phase 7 step 9.
     *
     * <p>Built per call rather than held: it is two references to objects this class
     * already owns, and the only callers are pairing screens, which are not on a
     * refresh path.
     */
    public com.aiusage.monitor.bridge.PairingStore pairingStore() {
        return new com.aiusage.monitor.bridge.PairingStore(bridgeRepository, accountManager);
    }

    /**
     * The pairing client, wired to this device: TLS pinned to the offered digest, and
     * the address rewriting of {@code DeviceProfile}. Phase 7 steps 6-8.
     *
     * <p>Built per call. It holds no state, and the screens that use it are the rarest
     * thing in the app — a widget refresh must not carry one.
     */
    public com.aiusage.monitor.bridge.PairingClient pairingClient() {
        return new com.aiusage.monitor.bridge.PairingClient(
                new com.aiusage.monitor.bridge.PinnedPairingTransport(),
                new com.aiusage.monitor.bridge.PairingClient.Clock() {
                    @Override
                    public long nowMs() {
                        return System.currentTimeMillis();
                    }
                },
                com.aiusage.monitor.bridge.DeviceProfile.addresses());
    }

    public AppSettings settings() {
        return settings;
    }

    /**
     * Imports the upstream app's saved state into the new tables, once.
     *
     * <p>Called from both entry points because either can be the first to run
     * after an upgrade: a user who never opens the app but has a widget on their
     * home screen must still find their key working.
     */
    public LegacyMigration.Result ensureMigrated() {
        return legacyMigration.runIfNeeded();
    }
}
