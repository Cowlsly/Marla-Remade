package android.app.supervision;

import java.time.Duration;

/**
 * Stub of the framework's {@code @SystemApi android.app.supervision.PackageUsagePolicy} - the
 * only concrete {@link Policy} the platform accepts.
 *
 * <p>The three TYPE_ values are inlined by javac, so they must match upstream exactly.
 *
 * <p>{@code TYPE_TIME_LIMIT} is gated upstream on the
 * {@code enable_supervision_package_usage_apis} aconfig flag, which AOSP ships enabled only in
 * {@code trunk_staging} - not an active value set for this build. MAOS turns it on via a value
 * file under {@code build/release/aconfig/cp2a/}, carried by
 * {@code vendor/modern-apps/patches/build_release.patch}. Without that, building a policy of
 * this type throws {@code IllegalStateException} from {@code performBuild}.
 */
public final class PackageUsagePolicy extends Policy {

    public static final int TYPE_ALLOWED = 0;
    public static final int TYPE_BLOCKED = 1;
    public static final int TYPE_TIME_LIMIT = 2;

    private PackageUsagePolicy() {}

    public int getType() {
        throw new UnsupportedOperationException("stub");
    }

    public String getPackageName() {
        throw new UnsupportedOperationException("stub");
    }

    public Duration getTimeLimit() {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * Note this deliberately does NOT declare {@code build()} - it is inherited from
     * {@link Policy.Builder}, exactly as upstream. See the note there.
     */
    public static final class Builder extends Policy.Builder<PackageUsagePolicy, Builder> {

        public Builder(String packageName, int type) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder(PackageUsagePolicy policy) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder setPackageName(String packageName) {
            throw new UnsupportedOperationException("stub");
        }

        public Builder setType(int type) {
            throw new UnsupportedOperationException("stub");
        }

        /** Rejected upstream unless the type is {@link #TYPE_TIME_LIMIT}, and capped at 24 h. */
        public Builder setTimeLimit(Duration timeLimit) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
