package com.aiusage.monitor.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.auth.AuthType;
import com.aiusage.monitor.model.Account;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.provider.deepseek.DeepSeekProvider;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Host-JVM tests for {@link ProviderRegistry}.
 *
 * <p>{@code ProviderRegistry} is a process-wide singleton and JUnit builds a new
 * test-class instance per method while the singleton survives the whole JVM, so
 * nothing here assumes a pristine registry. Every test either uses a unique stub
 * id of its own or explicitly tolerates {@code deepseek} already being present.
 * No reset hook was added to production code.
 */
public class ProviderRegistryTest {

    /** Minimal provider whose only job is to carry an id. */
    private static final class StubProvider implements UsageProvider {

        private final String id;

        StubProvider(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return "Stub " + id;
        }

        @Override
        public List<AuthType> getSupportedAuthTypes() {
            return Arrays.asList(AuthType.API_KEY);
        }

        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder().supports(AuthType.API_KEY).build();
        }

        @Override
        public UsageResult fetchUsage(Account account, AuthContext authContext) {
            throw new UnsupportedOperationException("registry tests never fetch");
        }
    }

    @Test
    public void registerThenFindReturnsTheSameInstance() {
        ProviderRegistry registry = ProviderRegistry.get();
        UsageProvider provider = new StubProvider("test-registry-find");

        registry.register(provider);

        assertSame(provider, registry.find("test-registry-find"));
    }

    @Test
    public void findOfUnknownOrNullIdReturnsNull() {
        ProviderRegistry registry = ProviderRegistry.get();

        assertNull(registry.find("test-registry-definitely-not-registered"));
        assertNull(registry.find(null));
    }

    @Test
    public void requireOfUnknownIdThrowsUnsupported() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.require("test-registry-definitely-not-registered");
            fail("expected UsageException for an unknown provider id");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNSUPPORTED, exception.getError());
        }
    }

    @Test
    public void requireOfNullIdThrowsUnsupported() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.require(null);
            fail("expected UsageException for a null provider id");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNSUPPORTED, exception.getError());
        }
    }

    @Test
    public void requireOfKnownIdReturnsTheRegisteredInstance() throws Exception {
        ProviderRegistry registry = ProviderRegistry.get();
        UsageProvider provider = new StubProvider("test-registry-require");

        registry.register(provider);

        assertSame(provider, registry.require("test-registry-require"));
    }

    @Test
    public void registeringADuplicateIdThrows() {
        ProviderRegistry registry = ProviderRegistry.get();

        registry.register(new StubProvider("test-registry-duplicate"));

        try {
            registry.register(new StubProvider("test-registry-duplicate"));
            fail("expected IllegalArgumentException for a duplicate provider id");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("test-registry-duplicate"));
        }
    }

    @Test
    public void registeringTheSameInstanceTwiceIsAllowed() {
        ProviderRegistry registry = ProviderRegistry.get();
        UsageProvider provider = new StubProvider("test-registry-idempotent");

        registry.register(provider);
        // Same instance: the guard only rejects a *different* provider claiming the id.
        registry.register(provider);

        assertSame(provider, registry.find("test-registry-idempotent"));
    }

    @Test
    public void registeringNullThrows() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.register(null);
            fail("expected IllegalArgumentException for a null provider");
        } catch (IllegalArgumentException exception) {
            assertNotNull(exception.getMessage());
        }
    }

    @Test
    public void registeringAProviderWithAnEmptyIdThrows() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.register(new StubProvider(""));
            fail("expected IllegalArgumentException for an empty provider id");
        } catch (IllegalArgumentException exception) {
            assertNotNull(exception.getMessage());
        }
    }

    @Test
    public void registeringAProviderWithANullIdThrows() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.register(new StubProvider(null));
            fail("expected IllegalArgumentException for a null provider id");
        } catch (IllegalArgumentException exception) {
            assertNotNull(exception.getMessage());
        }
    }

    @Test
    public void allReflectsRegistrationsInOrderAndIsUnmodifiable() {
        ProviderRegistry registry = ProviderRegistry.get();
        UsageProvider first = new StubProvider("test-registry-all-1");
        UsageProvider second = new StubProvider("test-registry-all-2");

        registry.register(first);
        registry.register(second);

        List<UsageProvider> all = registry.all();
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
        assertTrue(all.indexOf(first) < all.indexOf(second));

        // all() and isEmpty() must agree.
        assertEquals(all.isEmpty(), registry.isEmpty());
        assertFalse(registry.isEmpty());

        try {
            all.add(first);
            fail("expected all() to be unmodifiable");
        } catch (UnsupportedOperationException exception) {
            // Documented: Collections.unmodifiableList.
            assertNotNull(exception);
        }
    }

    @Test
    public void registerBuiltInsMakesDeepseekResolvable() throws Exception {
        ProviderRegistry registry = ProviderRegistry.get();

        if (registry.find(DeepSeekProvider.ID) == null) {
            registry.registerBuiltIns();
        }

        UsageProvider deepseek = registry.find(DeepSeekProvider.ID);
        assertNotNull(deepseek);
        assertEquals(DeepSeekProvider.ID, deepseek.getId());
        assertEquals("DeepSeek", deepseek.getName());
        assertSame(deepseek, registry.require(DeepSeekProvider.ID));
    }

    /**
     * Pins the registry's real behaviour, which is <em>not</em> idempotent:
     * {@code registerBuiltIns()} constructs a fresh {@link DeepSeekProvider} on
     * every call and {@code DeepSeekProvider} does not override
     * {@code equals}, so the second call trips the duplicate-id guard.
     */
    @Test
    public void secondRegisterBuiltInsCallThrowsDuplicateId() {
        ProviderRegistry registry = ProviderRegistry.get();

        try {
            registry.registerBuiltIns();
        } catch (IllegalArgumentException alreadyRegistered) {
            // An earlier test in this JVM got there first; that is the same state.
        }
        assertNotNull(registry.find(DeepSeekProvider.ID));

        try {
            registry.registerBuiltIns();
            fail("expected IllegalArgumentException: registerBuiltIns builds a new provider each call");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains(DeepSeekProvider.ID));
        }

        // The failure must not have damaged the registry.
        assertNotNull(registry.find(DeepSeekProvider.ID));
    }
}
