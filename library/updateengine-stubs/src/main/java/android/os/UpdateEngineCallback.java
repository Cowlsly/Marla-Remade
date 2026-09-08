package android.os;

/**
 * Compile-only stub. See the module's build.gradle.kts.
 *
 * <p>Mirrors {@code @SystemApi android.os.UpdateEngineCallback}. Both methods are abstract in
 * the real class, so a subclass must implement both.
 *
 * <p>Notes from the AOSP source that the signatures do not show:
 *
 * <ul>
 *   <li>Callbacks arrive on the {@link Handler} passed to
 *       {@link UpdateEngine#bind(UpdateEngineCallback, Handler)}, or on a binder thread when
 *       the single-argument {@code bind} is used. The single-argument form therefore delivers
 *       {@code onStatusUpdate} OFF the main thread.
 *   <li>{@code onStatusUpdate} fires immediately on bind with the current status, not only on
 *       change — so a freshly bound caller sees the state of an update already in progress.
 *   <li>{@code percent} is 0..1, not 0..100.
 * </ul>
 */
public abstract class UpdateEngineCallback {

    public abstract void onStatusUpdate(int status, float percent);

    public abstract void onPayloadApplicationComplete(int errorCode);
}
