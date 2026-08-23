package com.vayunmathur.sdk.cast

import android.os.ParcelFileDescriptor

/**
 * One thing this app can serve to the TV: a file descriptor and what is in it.
 *
 * Cast stopped re-encoding app content and became an origin server for it, so the TV fetches the
 * original bytes and decodes them itself. The bytes still belong to the app, though - `:music`
 * holds `READ_MEDIA_AUDIO` and the MediaStore URI, and YouPipe holds a download behind a token
 * Cast cannot mint - so the app hands over a descriptor and Cast puts it on the wire.
 *
 * A descriptor rather than bytes because the files already exist: copying a 4K video through a
 * `Bundle` would exceed Binder's transaction limit long before it finished, and copying it at all
 * would be pointless work.
 */
class CastResource(
    /**
     * A **seekable** descriptor, opened for reading.
     *
     * Seekable is the requirement, not a preference: the TV asks for byte ranges, so Cast reads at
     * offsets rather than from the start. A regular file works - `ParcelFileDescriptor.open`, or
     * `ContentResolver.openFileDescriptor` on a MediaStore URI. A pipe from
     * [ParcelFileDescriptor.createPipe] does not, and would turn every seek into a stall.
     *
     * Cast closes it when the session ends or the resource is replaced. The app should not.
     */
    val descriptor: ParcelFileDescriptor,
    /**
     * Total length in bytes, or **negative when the resource is still being written and the final
     * size is not known yet**.
     *
     * Stated by the app rather than measured, because a file still being written has a length its
     * author knows and `fstat` does not yet agree with. Cast reports it as the resource's length
     * and serves ranges against it.
     *
     * A negative length is how an app serves something it is producing as the TV fetches it - a
     * live transcode, say. Cast then serves the whole resource with no `Content-Length` and, when a
     * read reaches the current end of file, waits for more bytes instead of reporting the end. The
     * app must finish with [CastClient.resourceComplete], on failure as well as on success: a
     * reader that is never told turns into a stalled fetch.
     *
     * The price is that a resource with no length cannot answer a `Range`, so **the first play of
     * one cannot be seeked**. Offer a real length whenever there is one.
     */
    val length: Long,
    /**
     * The MIME type, which decides what the TV's player does with it.
     *
     * `audio/ogg` for an Opus track, `video/mp4` for fragmented MP4, `text/vtt` for captions,
     * `image/jpeg` for artwork. Getting it wrong is not fatal - ExoPlayer sniffs - but it costs a
     * round trip and can pick the wrong extractor.
     */
    val contentType: String,
)

/**
 * Answers Cast's requests for the bytes behind a resource id.
 *
 * The ids are the app's own: whatever it put in the media it told Cast to play. Called on a
 * background thread, once per resource per session, and never for a resource the app did not
 * mention.
 *
 * Returning null means "no such resource", which the TV sees as a `404`. That is the right answer
 * for an id that has expired - a queue that has moved on, a caption track no longer offered - and
 * a better one than a descriptor to the wrong file.
 */
fun interface CastResourceProvider {
    fun open(resourceId: String): CastResource?
}
