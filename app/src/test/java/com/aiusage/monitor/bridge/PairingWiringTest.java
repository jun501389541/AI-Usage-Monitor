package com.aiusage.monitor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The hostname-verification policy, pinned where a unit test cannot reach.
 * Phase 7 review P1 and docs/PHASE-7-PLAN.md A10.
 *
 * <p>{@code PinnedHostnameVerifier} itself is tested in {@code TlsPinningTest}; what
 * that test cannot see is how the rest of the app wires TLS. Three shapes are fatal
 * and none of them makes a behavioural test fail on the host JVM:
 * <ul>
 *   <li>{@code HttpsURLConnection.setDefaultHostnameVerifier(...)} — process-wide, so
 *       satisfying pairing would also switch off name checking for the DeepSeek API
 *       and every other request the app makes;</li>
 *   <li>a verifier that returns true, or that compares the hostname and not the key;</li>
 *   <li>installing the trust manager without the verifier, which is the half-config
 *       that fails on a LAN address with a correct pin.</li>
 * </ul>
 *
 * <p>So the assertions are textual, over every production source file, and each one
 * names the file it found the problem in.
 */
public class PairingWiringTest {

    private static final String MAIN_SOURCE = "app/src/main/java";

    @Test
    public void noCodeAnywhereSetsTheProcessWideHostnameVerifier() throws IOException {
        for (File file : sources()) {
            String text = code(read(file));
            assertTrue("a process-wide hostname verifier would disable certificate "
                            + "checking for every request in the app, not just pairing: "
                            + pathOf(file),
                    !text.contains("setDefaultHostnameVerifier"));
            assertTrue("the default SSLSocketFactory must not be replaced globally either: "
                            + pathOf(file),
                    !text.contains("setDefaultSSLSocketFactory"));
        }
    }

    @Test
    public void everyPerConnectionVerifierComesFromThePinnedOne() throws IOException {
        List<String> setters = new ArrayList<>();
        List<String> implementors = new ArrayList<>();
        for (File file : sources()) {
            String text = code(read(file));
            for (String line : text.split("\n")) {
                if (line.contains("setHostnameVerifier(")) {
                    setters.add(pathOf(file) + ": " + line.trim());
                }
            }
            if (lineImplementing(text, "implements HostnameVerifier")) {
                implementors.add(pathOf(file));
            }
        }
        assertEquals("only BridgeTls may install a hostname verifier, and it installs "
                        + "the pinned one; a second call site is a second policy\n"
                        + String.join("\n", setters),
                2, setters.size());
        for (String setter : setters) {
            assertTrue("a verifier installed outside BridgeTls is a policy nobody "
                    + "assembled: " + setter, setter.contains("BridgeTls.java"));
        }
        assertTrue("the pinned connection installs PinnedHostnameVerifier, never a "
                        + "variable that could hold anything: " + setters,
                hasSetters(setters, "new PinnedHostnameVerifier(pin)"));
        assertTrue("the probe's throwaway verifier is the other one, and it is named so "
                        + "that this list can see it: " + setters,
                hasSetters(setters, "new ProbeHostnameVerifier()"));
        assertEquals("exactly two verifier policies exist: the pinned one and the probe's",
                Arrays.asList("BridgeTls.java", "PinnedHostnameVerifier.java"),
                shortNames(implementors));
    }

    /**
     * The accept-any policy must stay confined to the method that has nothing to
     * compare yet. A textual check, because on the host JVM there is no socket to
     * prove it and the failure mode is invisible either way: an accept-any verifier
     * that reaches a credential-bearing connection disables pinning silently.
     */
    @Test
    public void theAcceptAnyVerifierIsReachableOnlyFromTheProbe() throws IOException {
        String tls = code(read(sourceOf("BridgeTls.java")));
        int probe = tls.indexOf("public static HttpsURLConnection openProbe(");
        int pinnedOpen = tls.indexOf("public static HttpsURLConnection open(");
        assertTrue(probe > 0);
        assertTrue("the pairing entry point and the read entry point are the same "
                        + "assembly; a second copy of it is a second policy",
                tls.contains("return openUrl(baseUrl + path, pin);"));
        int assembly = tls.indexOf("public static HttpsURLConnection openUrl(");
        assertTrue("openUrl is defined between open and openProbe, so the assertions "
                        + "below cover the code that actually assembles TLS",
                pinnedOpen < assembly && assembly < probe);
        String probeBody = tls.substring(probe,
                tls.indexOf("private static HttpsURLConnection open_(", probe));
        String pinnedBody = tls.substring(pinnedOpen, probe);
        assertEquals(1, occurrences(tls, "new ProbeHostnameVerifier()"));
        assertTrue("installed from openProbe and nowhere else",
                probeBody.contains("new ProbeHostnameVerifier()"));
        assertTrue("the pinned path cannot reach the accept-any verifier: " + pinnedBody,
                !pinnedBody.contains("ProbeHostnameVerifier"));
        assertTrue("the pinned path sets both halves of the check, in one place",
                pinnedBody.contains("setSSLSocketFactory")
                        && pinnedBody.contains("new PinnedHostnameVerifier(pin)"));
    }

    /**
     * The pairing credential must never travel on the unpinned path. Structural
     * rather than advisory: {@code openProbe} takes no headers and no body, and the
     * only writer of a request body is the pinned {@code post}.
     */
    @Test
    public void theUnverifiedProbePathCannotSendAnything() throws IOException {
        String tls = read(sourceOf("BridgeTls.java"));
        int probe = tls.indexOf("public static HttpsURLConnection openProbe(");
        int pinnedOpen = tls.indexOf("public static HttpsURLConnection open(");
        assertTrue(probe > 0);
        String probeBody = tls.substring(probe, tls.indexOf("private static HttpsURLConnection open_(", probe));
        assertTrue("the probe must not be able to set an Authorization header",
                !probeBody.contains("Authorization"));
        assertTrue("the probe must not be able to write a request body",
                !probeBody.contains("setDoOutput") && !probeBody.contains("getOutputStream"));
        String pinnedBody = tls.substring(pinnedOpen, probe);
        assertTrue("the pinned path sets both halves of the check, in one place",
                pinnedBody.contains("setSSLSocketFactory") && pinnedBody.contains("setHostnameVerifier"));
    }

    @Test
    public void pairingRefusesPlaintextBeforeItTouchesTheNetwork() throws IOException {
        String tls = read(sourceOf("BridgeTls.java"));
        int shared = tls.indexOf("private static HttpsURLConnection open_(");
        String body = tls.substring(shared, tls.indexOf("private static SSLContext contextFor(", shared));
        assertTrue("pairing carries a secret, so http:// is refused rather than upgraded "
                        + "silently: " + body,
                body.contains("https://") && body.contains("配对必须走 HTTPS"));
    }

    /**
     * The pairing screen is where A11's human step lives, so the rule keeping it honest
     * has to be machine-checked: the only code path that sends a typed short code must
     * sit behind the checkbox, and the checkbox must start unticked.
     *
     * <p>The client already refuses an unconfirmed exchange whatever the screen does
     * (PairingClientTest proves that); this proves the other half — that the screen
     * cannot reach the network with a confirmation nobody gave, and that a later edit
     * which moves the guard below the call turns red rather than quietly shipping a
     * pairing that asks nothing of a human.
     */
    @Test
    public void thePairingScreenCannotSendTheCodeWithoutTheConfirmation() throws IOException {
        String screen = code(read(sourceOf("PairActivity.java")));
        int method = screen.indexOf("private void startManualExchange()");
        int nextMethod = screen.indexOf("private ManualSource typedTarget()", method);
        assertTrue("startManualExchange exists to be read by this guard: " + method,
                method > 0 && nextMethod > method);
        String body = screen.substring(method, nextMethod);
        int guard = body.indexOf("digestConfirmed.isChecked()");
        int send = body.indexOf("exchangeManual(");
        assertTrue("the send has to be preceded by the confirmation check, in " + body,
                guard >= 0 && send >= 0 && guard < send);
        assertTrue("and the guard must return, not merely warn: " + body,
                body.indexOf("return;", guard) < send);
        assertTrue("the box starts unticked; a pre-ticked box is a formality, not a check",
                screen.contains("digestConfirmed.setChecked(false);"));
        assertTrue("the guard's condition has to be the box itself: a position check "
                        + "alone would still pass with if (false) in its place",
                body.contains("if (!digestConfirmed.isChecked()) {"));
        assertTrue("the confirmation is about the digest that was shown, so the digest "
                        + "and the tick must reach the client together",
                screen.contains("exchangeManual(target, shown, true, deviceName())"));
    }

    // --------------------------------------------------------------- plumbing

    /**
     * A source with its comment lines dropped. These guards ask "does any line of
     * code do this?", and {@code PinnedHostnameVerifier}'s own javadoc quotes the
     * global setter to explain why it is never called — counted as a call site, that
     * prose would make the guard fail while pointing at the correct file.
     */
    private static String code(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*")) {
                continue;
            }
            kept.add(line);
        }
        return join(kept, "\n");
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(parts.get(i));
        }
        return text.toString();
    }

    private static boolean lineImplementing(String text, String declaration) {
        return text.contains(declaration);
    }

    private static boolean hasSetters(List<String> setters, String installed) {
        for (String setter : setters) {
            if (setter.contains(installed)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> shortNames(List<String> paths) {
        List<String> names = new ArrayList<>();
        for (String path : paths) {
            names.add(path.substring(path.lastIndexOf('/') + 1));
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static List<File> sources() throws IOException {
        List<File> files = new ArrayList<>();
        collect(new File(root(), "com/aiusage/monitor"), files);
        assertTrue("scanning " + files.size() + " sources is not a scan", !files.isEmpty());
        return files;
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else if (child.getName().endsWith(".java")) {
                into.add(child);
            }
        }
    }

    private static File sourceOf(String fileName) {
        for (File file : new ArrayList<File>() {{
            collect(new File(root(), "com/aiusage/monitor"), this);
        }}) {
            if (file.getName().equals(fileName)) {
                return file;
            }
        }
        throw new AssertionError("no such source file: " + fileName);
    }

    /**
     * 「重新配对」 has to reach {@code PairingStore.rebind}. Review found on 2026-10-03 that
     * rebind existed with no caller at all: the account editor opened the pairing screen
     * without saying which account it was for, so a revoked token produced a second
     * account for the same computer while the original kept reading 「还没有与这台电脑配对」
     * with its history and slots stranded on it. Neither the store's tests nor the
     * device run could see this - the row is written correctly either way.
     */
    @Test
    public void thePairingScreenRebindsTheAccountItWasHanded() throws IOException {
        String pairing = code(read(sourceOf("PairActivity.java")));
        assertTrue("the pairing screen must take the account id from its intent: "
                        + "a re-pairing that ignores it invents an account",
                pairing.contains("getStringExtra(EXTRA_ACCOUNT_ID)"));
        assertTrue("and it must send that id to the rebind path rather than always "
                        + "creating a new account",
                pairing.contains(".rebind("));

        String editor = code(read(sourceOf("AccountEditActivity.java")));
        assertTrue("the account editor must hand the account it is showing to the "
                        + "pairing screen, or 「重新配对」 is only ever 「配对新电脑」",
                editor.contains("PairActivity.EXTRA_ACCOUNT_ID"));

        // The editor is only ever opened for a NEW account, so the reachable entry has
        // to be the one on the list row; asserting the editor alone would pass while no
        // screen could reach rebind at all.
        String list = code(read(sourceOf("AccountListActivity.java")));
        assertTrue("the account list needs a 重新配对 entry, or a revoked Codex account has "
                        + "no way back that keeps its history",
                list.contains("\"重新配对\""));
        assertTrue("and it must carry the account id into the pairing screen",
                list.contains("PairActivity.EXTRA_ACCOUNT_ID"));
    }

    /**
     * An insert that returns -1 has written nothing, and it is not an exception, so a
     * discarded return value is a silent "we paired with this computer" lie. The host JVM
     * cannot make SQLite refuse a row - {@code android.database.sqlite} is a stub there -
     * so this is textual, and it is scoped to the file that carries the pairing's row on
     * purpose: nine other insert sites across six storage classes also throw their result
     * away (recorded as debt in docs/PHASE-7-PLAN.md §10), and a repo-wide guard that reds
     * on all of them at once gets deleted rather than fixed.
     */
    @Test
    public void theBridgeWriteReportsTheRowItLost() throws IOException {
        String text = code(read(sourceOf("SqliteBridgeRepository.java")));
        assertTrue("save() must look at what SQLite returned",
                text.contains("insertWithOnConflict"));
        assertTrue("and a return of -1, the row-was-not-written case, has to fail "
                + "rather than be discarded", text.contains("< 0"));
    }

    private static File root() {
        String relative = MAIN_SOURCE;
        File fromRepo = new File(System.getProperty("user.dir"), relative);
        if (fromRepo.isDirectory()) {
            return fromRepo;
        }
        File fromModule = new File(System.getProperty("user.dir"), "src/main/java");
        if (fromModule.isDirectory()) {
            return fromModule;
        }
        fail("cannot find " + relative + " from " + System.getProperty("user.dir")
                + " - this guard would be scanning nothing");
        return null;
    }

    private static String pathOf(File file) {
        return file.getPath().replace('\\', '/');
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
