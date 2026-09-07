package com.android.media.remotedisplay;

import android.content.Context;
import android.os.IBinder;

import java.util.Collection;
import java.util.Collections;

/**
 * Compile-only stub of {@code com.android.media.remotedisplay.RemoteDisplayProvider}.
 * Not packaged; the real class comes from the {@code com.android.media.remotedisplay}
 * shared library, which the app pulls in with {@code <uses-library>}.
 *
 * <p>{@code RemoteDisplayProviderWatcher} in system_server binds a service exporting
 * {@link #SERVICE_INTERFACE} and drives it through this surface: the callbacks are how
 * the system asks for discovery and for a connection, and {@link #addDisplay} and
 * friends are how routes get back to {@code MediaRouter}.
 *
 * <p>The callbacks are declared concrete rather than abstract, matching AOSP - a provider that does
 * not do volume, for instance, simply does not override those. The class has no abstract members at
 * all, so a subclass cannot miss one.
 *
 * <p>Verified against frameworks/base/media/lib/remotedisplay/.../RemoteDisplayProvider.java and
 * the {@code DISCOVERY_MODE_*} values in
 * frameworks/base/media/java/android/media/RemoteDisplayState.java.
 */
public abstract class RemoteDisplayProvider {
    public static final String SERVICE_INTERFACE =
            "com.android.media.remotedisplay.RemoteDisplayProvider";

    public static final int DISCOVERY_MODE_NONE = 0;
    public static final int DISCOVERY_MODE_PASSIVE = 1;
    public static final int DISCOVERY_MODE_ACTIVE = 2;

    public RemoteDisplayProvider(Context context) {}

    public final Context getContext() {
        return null;
    }

    public IBinder getBinder() {
        return null;
    }

    public int getDiscoveryMode() {
        return DISCOVERY_MODE_NONE;
    }

    public Collection<RemoteDisplay> getDisplays() {
        return Collections.emptyList();
    }

    public RemoteDisplay findRemoteDisplay(String id) {
        return null;
    }

    public void addDisplay(RemoteDisplay display) {}

    public void updateDisplay(RemoteDisplay display) {}

    public void removeDisplay(RemoteDisplay display) {}

    public void onDiscoveryModeChanged(int mode) {}

    public void onConnect(RemoteDisplay display) {}

    public void onDisconnect(RemoteDisplay display) {}
}
