package android.app.supervision;

import java.util.List;

/**
 * Stub of the framework's {@code android.app.supervision.SupervisionManager}.
 *
 * <p>Obtained with {@code context.getSystemService(SupervisionManager.class)}.
 *
 * <p>{@link #getPolicies()} and {@link #setPolicy} both require
 * {@code android.permission.MANAGE_SUPERVISION}, which is carried by the
 * {@code SYSTEM_SUPERVISION} role rather than by privileged placement.
 *
 * <p>Note {@code setPolicy} with {@code TYPE_TIME_LIMIT} does not implement a timer upstream:
 * {@code SupervisionService.applyPackageUsagePolicy} suspends the package the moment the policy
 * is written, with a standing {@code TODO(b/482425646): Only suspend the package when limit is
 * reached}. Callers wanting real limits must drive the timing themselves.
 */
public class SupervisionManager {

    private SupervisionManager() {}

    public boolean isSupervisionEnabled() {
        throw new UnsupportedOperationException("stub");
    }

    public List<Policy> getPolicies() {
        throw new UnsupportedOperationException("stub");
    }

    public void setPolicy(Policy policy) {
        throw new UnsupportedOperationException("stub");
    }
}
