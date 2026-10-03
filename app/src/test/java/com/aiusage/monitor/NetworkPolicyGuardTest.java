package com.aiusage.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The cleartext exception is scoped to the local Bridge hosts, and that scoping is
 * the security property of Phase 6. It is asserted by reading the two files
 * because network policy is applied by the platform at request time - there is no
 * host-JVM call that would fail if someone widened it.
 *
 * <p>The failure mode this exists for is the one-line "fix": setting
 * usesCleartextTraffic to true. That makes the Bridge work and quietly permits
 * plain HTTP for every other request the app makes now or later.
 */
public class NetworkPolicyGuardTest {

    @Test
    public void globalCleartextStaysOff() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");

        assertTrue("the manifest must keep cleartext off by default",
                manifest.contains("android:usesCleartextTraffic=\"false\""));
        assertFalse("cleartext must not be enabled globally",
                manifest.contains("android:usesCleartextTraffic=\"true\""));
        assertTrue("the scoped policy has to be referenced, or it does nothing",
                manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""));
    }

    @Test
    public void cleartextIsPermittedOnlyForTheLocalBridgeHosts() throws IOException {
        String policy = read("app/src/main/res/xml/network_security_config.xml");

        assertTrue("10.0.2.2 is how an emulator reaches the host's loopback",
                policy.contains(">10.0.2.2<"));
        assertTrue(policy.contains(">localhost<"));
        assertFalse("a base-config with cleartext permitted is the global switch again",
                policy.contains("<base-config"));
        assertFalse("no wildcard domain may be permitted in cleartext",
                policy.contains(">*.<") || policy.contains(">*<*"));
    }

    private static String read(String relativePath) throws IOException {
        File file = new File(System.getProperty("user.dir"), relativePath);
        if (!file.isFile() && relativePath.startsWith("app/")) {
            file = new File(System.getProperty("user.dir"), relativePath.substring("app/".length()));
        }
        if (!file.isFile()) {
            fail("cannot find " + relativePath + " from " + System.getProperty("user.dir"));
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
