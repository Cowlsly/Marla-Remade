package android.nearby;

import java.util.List;

/**
 * Compile-only stub. See the module's build.gradle.kts.
 *
 * <p>Mirrors the powered-off finding surface of the real @SystemApi class. Behaviour notes that
 * are NOT visible from these signatures but that callers must handle, taken from the AOSP source:
 *
 * <ul>
 *   <li>Every method below throws {@link UnsupportedOperationException} when neither
 *       {@code ro.bluetooth.finder.supported} nor {@code persist.bluetooth.finder.supported} is
 *       true. There is no public predicate for this — the real
 *       {@code isPoweredOffFindingSupported()} is {@code private}.
 *   <li>All three require {@code android.permission.BLUETOOTH_PRIVILEGED}
 *       (signature|privileged), including the getter.
 *   <li>{@link #setPoweredOffFindingEphemeralIds} throws {@link IllegalArgumentException} unless
 *       every EID is exactly 20 bytes.
 *   <li>{@link #setPoweredOffFindingMode} throws {@link IllegalStateException} when called with
 *       {@link #POWERED_OFF_FINDING_MODE_ENABLED} while Bluetooth or location services are off.
 * </ul>
 */
public class NearbyManager {

    public static final int POWERED_OFF_FINDING_MODE_UNSUPPORTED = 0;

    public static final int POWERED_OFF_FINDING_MODE_DISABLED = 1;

    public static final int POWERED_OFF_FINDING_MODE_ENABLED = 2;

    private NearbyManager() {
        throw new UnsupportedOperationException("stub");
    }

    public void setPoweredOffFindingEphemeralIds(List<byte[]> eids) {
        throw new UnsupportedOperationException("stub");
    }

    public void setPoweredOffFindingMode(int poweredOffFindingMode) {
        throw new UnsupportedOperationException("stub");
    }

    public int getPoweredOffFindingMode() {
        throw new UnsupportedOperationException("stub");
    }
}
