package com.aiusage.monitor.bridge;

/**
 * The one place that reads the device's own identity, so that everything deciding
 * with it stays testable on a host JVM.
 *
 * <p>{@code android.os.Build}'s fields are constants on the device and stubs under
 * unit tests, which makes this the correct size of wrapper: one call, no logic.
 */
public final class DeviceProfile {

    private DeviceProfile() {
    }

    /**
     * Whether this build runs on an emulator, which decides only the loopback
     * rewriting in {@link AddressResolver}.
     */
    public static boolean isEmulator() {
        return AddressResolver.looksLikeEmulator(
                android.os.Build.FINGERPRINT, android.os.Build.HARDWARE,
                android.os.Build.PRODUCT);
    }

    public static AddressResolver addresses() {
        return AddressResolver.forEmulator(isEmulator());
    }
}
