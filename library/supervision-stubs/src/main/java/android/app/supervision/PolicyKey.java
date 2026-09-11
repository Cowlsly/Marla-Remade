package android.app.supervision;

/**
 * Stub of the framework's {@code android.app.supervision.PolicyKey}.
 *
 * <p>Only the two accessors are declared. The builder is upstream but we never construct one -
 * keys reach us via {@link Policy#getPolicyKey()}.
 */
public final class PolicyKey {

    private PolicyKey() {}

    public String getType() {
        throw new UnsupportedOperationException("stub");
    }

    public String getPackageName() {
        throw new UnsupportedOperationException("stub");
    }
}
