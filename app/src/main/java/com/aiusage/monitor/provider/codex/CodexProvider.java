package com.aiusage.monitor.provider.codex;

import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.AuthContext;
import com.aiusage.monitor.provider.ProviderCapabilities;
import com.aiusage.monitor.provider.UsageException;
import com.aiusage.monitor.provider.UsageProvider;
import com.aiusage.monitor.util.BridgeTransport;
import com.aiusage.monitor.util.HttpBridgeTransport;

import java.util.Arrays;
import java.util.List;

/**
 * The Codex platform, read through a Windows AI Usage Bridge.
 *
 * <p>One provider serves both Codex access paths. Spec §53 rule 20 and §54
 * forbid making "Codex Bridge" and "Codex Direct" two providers: a user has one
 * Codex account, and the transport it is reached by is not part of its identity.
 * So this class holds only the platform's declared abilities, and delegates the
 * actual reading to {@link BridgeCodexDataSource}. A later Direct source attaches
 * here, not beside here.
 *
 * <p>Codex reports no money. The capabilities say so, which is what stops the UI
 * from assuming a balance exists: {@code reportsBalance(false)} is the whole
 * reason a quota-only account can be rendered honestly.
 */
public final class CodexProvider implements UsageProvider {

    /** Stable id, matching what the Bridge reports at {@code /v1/providers}. */
    public static final String ID = BridgeUsageParser.PROVIDER_ID;

    private static final ProviderCapabilities CAPABILITIES = ProviderCapabilities.builder()
            .supports(AuthType.BRIDGE_TOKEN)
            .reportsBalance(false)
            .reportsQuotaWindows(true)
            // The Bridge's own payload carries plan and credit fields that are not
            // declared as widget metrics here; nothing renders them in Phase 6, so
            // claiming a metric list would be a promise the widget cannot keep.
            .reportsMetrics(false)
            // The Bridge caches for five minutes and re-reading it spawns a Codex
            // app-server subprocess each time, so polling faster than that buys
            // nothing and costs a process. Spec §54 forbids per-minute widget
            // polling for freshness that a quota window does not have.
            .recommendedRefreshIntervalMs(5 * 60 * 1000L)
            .build();

    private final BridgeCodexDataSource dataSource;

    public CodexProvider() {
        this(new HttpBridgeTransport());
    }

    /** Test seam: a fake transport lets the whole provider run on a plain JVM. */
    public CodexProvider(BridgeTransport transport) {
        this.dataSource = new BridgeCodexDataSource(transport);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "OpenAI Codex";
    }

    @Override
    public List<AuthType> getSupportedAuthTypes() {
        return Arrays.asList(AuthType.BRIDGE_TOKEN);
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public UsageResult fetchUsage(Account account, AuthContext authContext) throws UsageException {
        if (authContext.getAuthType() != AuthType.BRIDGE_TOKEN) {
            throw new UsageException(UsageError.UNSUPPORTED,
                    "Codex 账户目前只支持通过电脑端 Bridge 读取");
        }

        // Address validation belongs to BridgeCodexDataSource, which rejects an
        // empty or malformed address before it touches the transport. Checking it
        // here as well was dead code: AuthContext.get() answers "" for a missing
        // key, so the "null" branch could not run, and a mutation that defaulted
        // the address still passed every test until that was noticed.
        //
        // The digest travels the same way on purpose. It is not a secret and not a
        // credential — it is the thing that decides *which machine* may answer — so
        // the provider layer treats it exactly like the address: read it from the
        // context, hand it down, let the data source refuse a combination that cannot
        // be trusted (docs/PHASE-7-PLAN.md A5/A9).
        return dataSource.fetch(authContext.get(AuthContext.KEY_BRIDGE_URL),
                authContext.get(AuthContext.KEY_DEVICE_TOKEN),
                authContext.get(AuthContext.KEY_BRIDGE_PIN),
                account.getId(), System.currentTimeMillis());
    }
}
