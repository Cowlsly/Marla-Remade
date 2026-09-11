package android.app.supervision;

/**
 * Stub of the framework's {@code @SystemApi android.app.supervision.Policy}.
 *
 * <p>Abstract upstream, and never constructed by us - it arrives through
 * {@link SupervisionAppService#onPolicyChanged} and is produced by
 * {@link PackageUsagePolicy.Builder}. The constructors are package-private upstream and are
 * omitted here for that reason.
 *
 * <p>Upstream this implements {@link android.os.Parcelable}. The stub deliberately does not:
 * nothing in this app ever parcels a policy, and declaring the interface without the required
 * {@code CREATOR} is exactly the mismatch lint's ParcelCreator check exists to catch. Declaring
 * only what we touch keeps the stub honest - if a caller ever does need to parcel one, that is
 * the moment to add the field and check it against upstream, not now.
 */
public abstract class Policy {

    /** The only policy identifier that exists upstream. Inlined by javac - value matters. */
    public static final String PACKAGE_POLICY_IDENTIFIER = "package";

    Policy() {}

    public long getVersion() {
        throw new UnsupportedOperationException("stub");
    }

    public String getIdentifier() {
        throw new UnsupportedOperationException("stub");
    }

    public PolicyKey getPolicyKey() {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * The generic self-typed builder base.
     *
     * <p>Mirrored exactly rather than flattened. {@code build()} erases to
     * {@code ()Landroid/app/supervision/Policy;} and {@link PackageUsagePolicy.Builder} does not
     * override it, so a call through the subclass compiles to an invokevirtual on <em>this</em>
     * method plus a checkcast. A stub that moved {@code build()} down into the subclass would
     * emit a call to a method the real class does not declare.
     */
    public abstract static class Builder<P extends Policy, B extends Builder<P, B>> {

        Builder() {}

        @SuppressWarnings("unchecked")
        public B setVersion(long version) {
            throw new UnsupportedOperationException("stub");
        }

        public P build() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
