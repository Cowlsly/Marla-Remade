package android.service.euicc;

/**
 * Compile-only stub of the {@code @SystemApi}
 * {@code android.service.euicc.EuiccProfileInfo}. Only the members the LPA
 * constructs are declared. Not packaged; the framework provides the real class.
 *
 * <p>Note the package: this class lives in {@code android.service.euicc}, unlike its
 * siblings {@code DownloadableSubscription} and {@code EuiccInfo} which really are in
 * {@code android.telephony.euicc}. Declaring the stub in the latter compiles, then fails
 * at runtime with {@code ClassNotFoundException} the first time the framework calls
 * {@code onGetEuiccProfileInfoList}.
 */
public final class EuiccProfileInfo {
    public static final int PROFILE_STATE_UNSET = -1;
    public static final int PROFILE_STATE_DISABLED = 0;
    public static final int PROFILE_STATE_ENABLED = 1;

    public static final int PROFILE_CLASS_UNSET = -1;
    public static final int PROFILE_CLASS_TESTING = 0;
    public static final int PROFILE_CLASS_PROVISIONING = 1;
    public static final int PROFILE_CLASS_OPERATIONAL = 2;

    public static final class Builder {
        public Builder(String iccid) {}

        public Builder setNickname(String nickname) {
            return this;
        }

        public Builder setServiceProviderName(String serviceProviderName) {
            return this;
        }

        public Builder setProfileName(String profileName) {
            return this;
        }

        public Builder setState(int state) {
            return this;
        }

        public Builder setProfileClass(int profileClass) {
            return this;
        }

        public EuiccProfileInfo build() {
            return null;
        }
    }
}
