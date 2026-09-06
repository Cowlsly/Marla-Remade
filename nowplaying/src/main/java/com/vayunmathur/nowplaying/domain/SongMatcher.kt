package com.vayunmathur.nowplaying.domain

/**
 * Identifies *which* song is playing.
 *
 * Nothing implements this yet. Identification matches an embedding against an on-device database
 * of song fingerprints, and there is no honest substitute - a matcher that guessed would put a
 * title in front of the user that the app cannot stand behind. So this app detects that music is
 * playing and stops there, and identification sits behind this interface.
 *
 * @see NoSongMatcher
 */
interface SongMatcher {
    /** The song [embedding] belongs to, or null when nothing matches confidently. */
    suspend fun match(embedding: FloatArray): SongMatch?
}

/** A song the matcher is confident enough to name. */
data class SongMatch(
    val title: String,
    val artist: String,
    val confidence: Float,
)

/**
 * The only implementation: it never matches.
 *
 * TODO: replace with a real matcher once a song-fingerprint database is available. That needs a
 *  shard store, product-quantised nearest-neighbour search over it, and an embedding model — none
 *  of which exist yet. Until then this returning null is the app's accurate answer.
 */
object NoSongMatcher : SongMatcher {
    override suspend fun match(embedding: FloatArray): SongMatch? = null
}
