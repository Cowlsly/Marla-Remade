package com.android.media.remotedisplay;

/**
 * Compile-only stub of {@code com.android.media.remotedisplay.RemoteDisplay}, one
 * route published by a {@link RemoteDisplayProvider}. Not packaged; the real class
 * comes from the {@code com.android.media.remotedisplay} shared library, which the
 * app pulls in with {@code <uses-library>}.
 *
 * <p>The status values mirror {@code android.media.RemoteDisplayState.RemoteDisplayInfo}
 * and are what {@code MediaRouter} turns into a route's state, so
 * {@code WifiDisplaySettings} draws its row from them.
 *
 * <p>Volume is not declared. Nothing in this app does volume control, and javac inlines a stub's
 * {@code static final int} into the caller - so a constant declared here but never read against the
 * real class is a wrong value that no build would catch. The real class does have
 * {@code PLAYBACK_VOLUME_*} and the volume/presentation-display accessors; add them only alongside
 * a caller.
 *
 * <p>Verified against frameworks/base/media/lib/remotedisplay/.../RemoteDisplay.java and the
 * {@code STATUS_*} values in frameworks/base/media/java/android/media/RemoteDisplayState.java.
 */
public class RemoteDisplay {
    public static final int STATUS_NOT_AVAILABLE = 0;
    public static final int STATUS_IN_USE = 1;
    public static final int STATUS_AVAILABLE = 2;
    public static final int STATUS_CONNECTING = 3;
    public static final int STATUS_CONNECTED = 4;

    public RemoteDisplay(String id, String name) {}

    public String getId() {
        return null;
    }

    public String getName() {
        return null;
    }

    public void setName(String name) {}

    public String getDescription() {
        return null;
    }

    public void setDescription(String description) {}

    public int getStatus() {
        return STATUS_NOT_AVAILABLE;
    }

    public void setStatus(int status) {}
}
