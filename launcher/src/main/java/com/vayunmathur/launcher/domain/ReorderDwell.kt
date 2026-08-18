package com.vayunmathur.launcher.domain

/**
 * The 650ms a drag has to hold still before the rearrangement it implies is committed.
 *
 * Why a dwell exists at all: a drag from one corner of the page to another passes over every cell
 * on the way, and reordering on each of them would rearrange the whole page in transit and then
 * rearrange it back. Launcher3 gates the reorder on `CellLayout.REORDER_TIMEOUT` for exactly that
 * reason.
 *
 * Why it lives in its own object rather than inside [GridPreview]: `plan` is the single currency
 * for both the live preview and the commit, and it stays pure and clockless so it can go on being
 * that. The clock belongs here, fed from the pointer stream.
 *
 * The consequence, and the reason this is a correctness fix rather than tidiness: **with a dwell
 * the release point is no longer necessarily the dwelt point.** A drop must therefore commit
 * [committed] — what the user actually watched happen — and not a plan recomputed at whatever cell
 * the finger happened to be over when it lifted.
 */
class ReorderDwell(private val timeoutMillis: Long) {

    /**
     * The rearrangement the page is showing, and the one a drop must write. Null until something
     * has been dwelt on, and kept afterwards even as the finger moves on — which is what makes a
     * release somewhere else still commit what was previewed.
     */
    var committed: DropPlan? = null
        private set

    private var candidate: DropPlan? = null
    private var since = 0L

    /**
     * Feeds one frame of the pointer stream, and returns whether this call is the one that
     * committed.
     *
     * A [candidate] that displaces nothing commits on arrival rather than waiting: there is no
     * rearrangement to be careful about, and making a move into an empty cell wait 650ms would
     * make the whole grid feel unresponsive.
     */
    fun update(nowMillis: Long, candidate: DropPlan?): Boolean {
        if (candidate == null) {
            this.candidate = null
            return false
        }
        if (candidate != this.candidate) {
            this.candidate = candidate
            since = nowMillis
        }
        if (candidate == committed) return false
        if (candidate.displaced.isNotEmpty() && nowMillis - since < timeoutMillis) return false
        committed = candidate
        return true
    }

    /** Forgets everything, for the start of the next drag. */
    fun reset() {
        committed = null
        candidate = null
        since = 0L
    }
}
