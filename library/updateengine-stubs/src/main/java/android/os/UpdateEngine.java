package android.os;

/**
 * Compile-only stub. See the module's build.gradle.kts.
 *
 * <p>Mirrors the surface of the real {@code @SystemApi android.os.UpdateEngine} that
 * {@code :updater} calls. Behaviour that is NOT visible from these signatures but that callers
 * must handle, taken from the AOSP source:
 *
 * <ul>
 *   <li>The constructor binds to the {@code android.os.UpdateEngineService} binder. It throws
 *       {@link IllegalStateException} when that service is unavailable, which is the case on
 *       any build where the caller is not privileged.
 *   <li>{@link #applyPayload} is asynchronous. Completion and failure both arrive on
 *       {@link UpdateEngineCallback#onPayloadApplicationComplete}, never as a thrown exception,
 *       so a caller that only tries/catches will silently believe it succeeded.
 *   <li>Only ONE payload may be applied at a time, process-wide and device-wide. Calling
 *       {@link #applyPayload} while an update is in progress throws
 *       {@link ServiceSpecificException}. {@link #cancel()} or {@link #resetStatus()} first.
 *   <li>{@link #resetStatus()} is only valid when an update has been applied but not yet booted
 *       ({@link UpdateStatusConstants#UPDATED_NEED_REBOOT}); it throws otherwise.
 *   <li>The {@code url} passed to {@link #applyPayload} may be an {@code http(s)://} address —
 *       update_engine streams the payload itself — or a {@code file://} path.
 * </ul>
 */
public class UpdateEngine {

    /**
     * Error codes delivered to
     * {@link UpdateEngineCallback#onPayloadApplicationComplete(int)}.
     *
     * <p>Values are inlined by javac, so they must match AOSP exactly. Note the gap: there is
     * no 2 or 3, and the tail jumps to 51.
     */
    public static final class ErrorCodeConstants {
        public static final int SUCCESS = 0;
        public static final int ERROR = 1;
        public static final int FILESYSTEM_COPIER_ERROR = 4;
        public static final int POST_INSTALL_RUNNER_ERROR = 5;
        public static final int PAYLOAD_MISMATCHED_TYPE_ERROR = 6;
        public static final int INSTALL_DEVICE_OPEN_ERROR = 7;
        public static final int KERNEL_DEVICE_OPEN_ERROR = 8;
        public static final int DOWNLOAD_TRANSFER_ERROR = 9;
        public static final int PAYLOAD_HASH_MISMATCH_ERROR = 10;
        public static final int PAYLOAD_SIZE_MISMATCH_ERROR = 11;
        public static final int DOWNLOAD_PAYLOAD_VERIFICATION_ERROR = 12;
        public static final int PAYLOAD_TIMESTAMP_ERROR = 51;
        public static final int UPDATED_BUT_NOT_ACTIVE = 52;
        public static final int NOT_ENOUGH_SPACE = 60;
        public static final int DEVICE_CORRUPTED = 61;
    }

    /** Status values delivered to {@link UpdateEngineCallback#onStatusUpdate(int, float)}. */
    public static final class UpdateStatusConstants {
        public static final int IDLE = 0;
        public static final int CHECKING_FOR_UPDATE = 1;
        public static final int UPDATE_AVAILABLE = 2;
        public static final int DOWNLOADING = 3;
        public static final int VERIFYING = 4;
        public static final int FINALIZING = 5;
        public static final int UPDATED_NEED_REBOOT = 6;
        public static final int REPORTING_ERROR_EVENT = 7;
        public static final int ATTEMPTING_ROLLBACK = 8;
        public static final int DISABLED = 9;
    }

    public UpdateEngine() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean bind(final UpdateEngineCallback callback, final Handler handler) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean bind(final UpdateEngineCallback callback) {
        throw new UnsupportedOperationException("stub");
    }

    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) {
        throw new UnsupportedOperationException("stub");
    }

    public void cancel() {
        throw new UnsupportedOperationException("stub");
    }

    public void suspend() {
        throw new UnsupportedOperationException("stub");
    }

    public void resume() {
        throw new UnsupportedOperationException("stub");
    }

    public void resetStatus() {
        throw new UnsupportedOperationException("stub");
    }

    public void setShouldSwitchSlotOnReboot(String payloadMetadataFilename) {
        throw new UnsupportedOperationException("stub");
    }
}
