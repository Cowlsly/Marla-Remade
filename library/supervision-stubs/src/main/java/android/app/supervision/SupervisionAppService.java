package android.app.supervision;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Stub of the framework's {@code @SystemApi android.app.supervision.SupervisionAppService}.
 *
 * <p>Upstream doc: a service the holders of {@code ROLE_SYSTEM_SUPERVISION} or
 * {@code ROLE_SUPERVISION} <em>must</em> extend. The system searches each role holder for
 * {@link #ACTION_SUPERVISION_APP_SERVICE} and keeps a bound connection to it in the foreground,
 * rebinding after a backoff if the process dies.
 *
 * <p>{@code onBind} is <b>final</b> upstream - it returns an {@code ISupervisionListener.Stub}
 * that dispatches to the callbacks below. It is final here for that reason: were the stub to
 * leave it open, a subclass could override it, which compiles cleanly and fails on device with
 * {@code IncompatibleClassChangeError}. Override {@code onServiceBound} if you need the intent.
 */
public class SupervisionAppService extends Service {

    /** Inlined by javac. Must match what {@code SupervisionAppServiceFinder} queries for. */
    public static final String ACTION_SUPERVISION_APP_SERVICE =
            "android.app.action.SUPERVISION_APP_SERVICE";

    @Override
    public final IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("stub");
    }

    /** Called when the service is bound. Exists because {@link #onBind} is final. */
    public void onServiceBound(Intent intent) {}

    public void onSupervisionEnabled() {}

    public void onSupervisionDisabled() {}

    public void onPolicyChanged(Policy policy) {}
}
