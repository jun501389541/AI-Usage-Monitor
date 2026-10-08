package com.aiusage.monitor.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The detail page has to know which provider it is showing, in the query path as
 * much as in the wording.
 *
 * <p>A Bridge account has no key field: its address and token live in the
 * credential the refresh chain opens. Before this pin the screen read only
 * {@code keyInput}, so tapping 查询余额 on a Codex account did nothing at all,
 * and the layout above it still offered a DeepSeek key box, a "CNY 余额" caption
 * and a toast about an API key that account never had.
 *
 * <p>MainActivity needs an Android context, so the wiring is pinned by reading
 * its source, the way {@code QuotaRenderingWiringTest} and the ProviderRegistry
 * guards do. Each assertion is ordered or counted on purpose: a check that only
 * asks whether a string exists anywhere in the file would still pass once the
 * provider branch is deleted and the same words move to the other side of it.
 */
public class BridgeQueryPathWiringTest {

    /** A Bridge query must open the stored credential, not a typed key. */
    @Test
    public void bridgeAccountsQueryThroughTheirStoredCredential() throws IOException {
        String source = read();

        int bridgeBranch = source.indexOf("runQuery(automatic, queried -> refreshManager.refresh(queried))");
        int typedKeyBranch = source.indexOf("String apiKey = keyInput.getText()");
        assertTrue("the Bridge branch must ask the manager to open the credential",
                bridgeBranch > 0);
        assertTrue("the key field may only be read on the DeepSeek path", typedKeyBranch > 0);
        assertTrue("the credential path has to come before any key-field read, "
                        + "otherwise a Codex account falls through to it",
                bridgeBranch < typedKeyBranch);

        assertTrue("an unconfigured Bridge account must say what to fill in",
                source.contains("请先在「编辑账户」里填写 Bridge 地址"));
        assertEquals("the DeepSeek-only key toast must exist once, on the key path only",
                1, count(source, "请先输入 DeepSeek API Key"));
    }

    /** The credential card shows a key box for DeepSeek and an address for Bridge. */
    @Test
    public void credentialCardIsBuiltPerProvider() throws IOException {
        String source = read();

        int bridgeBlock = source.indexOf("if (isBridge) {");
        int queryButton = source.indexOf("queryButton = actionButton(queryLabel(), true)");
        assertTrue("the Bridge screen must not render an API KEY box", bridgeBlock > 0);
        assertTrue("the key box has to sit inside the else branch of the provider check",
                bridgeBlock < queryButton);
        String cardBlock = source.substring(bridgeBlock, queryButton);
        assertTrue("the provider branch must cover the key box", cardBlock.contains("\"API KEY\""));
        assertTrue("the provider branch must cover the Bridge address line",
                cardBlock.contains("bridgeAddressView"));
        assertEquals("\"API KEY\" must appear only inside the provider branch",
                1, count(source, "text(\"API KEY\""));

        assertTrue("the key field's listeners are DeepSeek-only",
                source.contains("if (!isBridge) {\n            wireKeyField();"));
    }

    /** Provider-only words stay provider-only. */
    @Test
    public void deepSeekOnlyLabelsAppearOnceAndAreChosenByProvider() throws IOException {
        String source = read();

        assertEquals("the kicker names DeepSeek in exactly one branch",
                1, count(source, "\"DEEPSEEK API\""));
        assertEquals("the balance caption must not hard-code CNY for every account",
                1, count(source, "text(\"CNY 余额\""));
        assertTrue("Codex must hide the balance and daily currency row",
                source.contains("balanceRow.setVisibility(isBridge ? View.GONE : View.VISIBLE);"));
        assertEquals("the query button label comes from queryLabel(), not from a literal",
                1, count(source, "\"查询余额\""));
        assertTrue("both labels must be produced by the same branch",
                source.contains("return isBridgeAccount() ? \"查询额度\" : \"查询余额\";"));
        assertTrue("an unqueried Bridge screen must not promise a CNY reading",
                source.contains("isBridge ? \"等待读取额度窗口\" : \"等待读取 CNY 余额\""));
    }

    /** The automatic refresh gate has to use the same rule the button uses. */
    @Test
    public void autoRefreshGateUsesThePerProviderRule() throws IOException {
        String source = read();

        assertEquals("the automatic callback must ask canQueryNow()",
                1, count(source, "if (!loading && canQueryNow())"));
        assertTrue("onResume must respect manual-only mode and the provider gate",
                source.contains("if (refreshIntervalMs != RefreshPolicy.MANUAL_ONLY && !loading && canQueryNow())"));
        assertTrue("queued automatic callbacks must also respect manual-only mode",
                source.contains("if (isFinishing() || refreshIntervalMs == RefreshPolicy.MANUAL_ONLY)"));
        assertTrue("the gate reads the Bridge address, not the key field, for a Bridge account",
                source.contains("return bridgeConfigured;"));
        assertEquals("the key field may be the gate only on the DeepSeek path",
                1, count(source, "!TextUtils.isEmpty(keyInput.getText().toString().trim())"));
        assertTrue("the credential must be loaded before the gate can answer",
                source.indexOf("bridgeConfigured = !TextUtils.isEmpty(bridgeUrl);") > 0);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            total++;
        }
        return total;
    }

    private static String read() throws IOException {
        String relativePath = "app/src/main/java/com/aiusage/monitor/ui/MainActivity.java";
        File fromRoot = new File(System.getProperty("user.dir"), relativePath);
        File fromModule = new File(System.getProperty("user.dir"), relativePath.substring("app/".length()));
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        if (!candidate.isFile()) {
            fail("cannot find " + relativePath + " from " + System.getProperty("user.dir")
                    + " - this guard would be scanning nothing");
        }
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue("wrong file scanned: " + candidate, text.contains("class MainActivity"));
        return text;
    }
}
