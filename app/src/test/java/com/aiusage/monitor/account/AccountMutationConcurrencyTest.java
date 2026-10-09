package com.aiusage.monitor.account;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.auth.CredentialStore;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.provider.AuthContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

/** Pairing and metadata edits must both survive a concurrent read-modify-write. */
public class AccountMutationConcurrencyTest {
    @Test public void renameCannotDiscardANewlyPairedCredential() throws Exception {
        Account result = editWhilePairing((manager, id) -> manager.rename(id, "renamed"));
        assertEquals("renamed", result.getDisplayName());
    }

    @Test public void disablingCannotDiscardANewlyPairedCredential() throws Exception {
        Account result = editWhilePairing((manager, id) -> manager.setEnabled(id, false));
        assertFalse(result.isEnabled());
    }

    @Test public void attachingBridgeCannotDiscardANewlyPairedCredential() throws Exception {
        Account result = editWhilePairing((manager, id) -> manager.attachBridge(id, "bridge"));
        assertEquals("bridge", result.getBridgeId());
    }

    private Account editWhilePairing(BiConsumer<AccountManager, String> edit) throws Exception {
        BlockingAccounts repository = new BlockingAccounts();
        CredentialStore credentials = new CredentialStore() {
            @Override public String create(AuthType type, String payload) { return "paired-credential"; }
            @Override public void update(String id, String payload) { }
            @Override public AuthContext open(String id, AuthType type) { throw new UnsupportedOperationException(); }
            @Override public void delete(String id) { }
            @Override public boolean isUsable(String id) { return "paired-credential".equals(id); }
        };
        AccountManager manager = new AccountManager(repository, credentials);
        Account account = manager.createAccount("codex", "original", AuthType.BRIDGE_TOKEN);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch pairingFinished = new CountDownLatch(1);
        Thread editor = new Thread(() -> {
            try { edit.accept(manager, account.getId()); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "account-edit");
        Thread pairing = new Thread(() -> {
            try { manager.replaceCredential(account.getId(), "{}"); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { pairingFinished.countDown(); }
        }, "account-pairing");
        editor.start();
        try {
            assertTrue("Editor did not reach its row read", repository.read.await(5, TimeUnit.SECONDS));
            pairing.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // A correctly serialized pairing waits for the editor's lock; the
            // old implementation finishes pairing before the stale edit writes.
            while (pairing.getState() != Thread.State.BLOCKED
                    && !pairingFinished.await(10, TimeUnit.MILLISECONDS)
                    && System.nanoTime() < deadline) { }
        } finally {
            repository.resume.countDown();
            editor.join(5000);
            pairing.join(5000);
        }
        assertFalse("Editor did not finish", editor.isAlive());
        assertFalse("Pairing did not finish", pairing.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        Account stored = manager.find(account.getId());
        assertEquals("Metadata edit discarded the credential written by pairing",
                "paired-credential", stored.getCredentialId());
        return stored;
    }

    private static final class BlockingAccounts implements AccountRepository {
        final Map<String, Account> rows = new ConcurrentHashMap<>();
        final CountDownLatch read = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        @Override public List<Account> findAll() { return new ArrayList<>(rows.values()); }
        @Override public List<Account> findEnabled() {
            List<Account> enabled = new ArrayList<>();
            for (Account account : rows.values()) if (account.isEnabled()) enabled.add(account);
            return enabled;
        }
        @Override public Account findById(String id) {
            Account snapshot = rows.get(id);
            if ("account-edit".equals(Thread.currentThread().getName())) {
                read.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Edit was not resumed");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return snapshot;
        }
        @Override public void save(Account account) { rows.put(account.getId(), account); }
        @Override public void delete(String id) { rows.remove(id); }
        @Override public void reorder(List<String> ids) { }
        @Override public int count() { return rows.size(); }
    }
}
