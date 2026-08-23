package com.vayunmathur.cast.service

import android.os.Bundle
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vayunmathur.cast.protocol.MediaResource
import com.vayunmathur.cast.protocol.MediaResourceResolver
import com.vayunmathur.sdk.cast.CastContract
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ClientResources"

/**
 * How long the proxy waits for the app to hand over a descriptor.
 *
 * Generous for what it is - opening a MediaStore descriptor is one file open - but bounded, because
 * the caller is a thread serving an HTTP request. Waiting for ever there would turn an app that has
 * stopped answering into a TV sitting on a dead connection, which is the failure the old path was
 * full of.
 */
private const val REQUEST_TIMEOUT_MS = 5_000L

/**
 * How long a read on a growing resource waits at end of file before giving up.
 *
 * The producer is a transcode running several times faster than real time, so a healthy one
 * never comes close to this: it exists for the one that died without saying so. Bounded because
 * the waiter is a thread serving an HTTP request, and waiting for ever there is how a dead
 * encoder turns into a television holding an open connection with nothing on screen. Reaching it
 * surfaces as a truncated body, which the player reports as a load failure - the honest outcome,
 * and a much better one than a spinner nothing ever resolves.
 */
private const val GROWING_READ_TIMEOUT_MS = 10_000L

/** How long to sleep between looks at a file that has not grown yet. */
private const val GROWING_POLL_MS = 25L

/**
 * Gets the bytes for the media proxy from the app being cast.
 *
 * The proxy needs a resource on demand, from an HTTP serving thread; the app answers over a
 * `Messenger`, asynchronously, on its own main looper. This is the join between the two: a blocking
 * [resolve] on one side, a request/response pair correlated by id on the other.
 *
 * Descriptors are cached per resource for the life of the session. One per resource rather than one
 * per range request is the whole reason a descriptor works here at all - a range is served by
 * reading at an offset, so the same descriptor answers every range of the same file.
 */
class ClientResourceResolver(
    /** Sends a message to the bound client, returning false when there is no client left. */
    private val send: (Message) -> Boolean,
) : MediaResourceResolver {

    private val nextRequestId = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, ArrayBlockingQueue<Answer>>()
    private val cache = ConcurrentHashMap<String, DescriptorResource>()

    /** Wrapped rather than nullable so that "the app said no" is distinguishable from a timeout. */
    private class Answer(val resource: DescriptorResource?)

    override fun resolve(resourceId: String): MediaResource? {
        cache[resourceId]?.let { return it }

        val requestId = nextRequestId.getAndIncrement()
        val mailbox = ArrayBlockingQueue<Answer>(1)
        waiting[requestId] = mailbox
        try {
            val request = Message.obtain(null, CastContract.MSG_RESOURCE_REQUEST).apply {
                data = Bundle().apply {
                    putInt(CastContract.KEY_REQUEST_ID, requestId)
                    putString(CastContract.KEY_RESOURCE_ID, resourceId)
                }
            }
            if (!send(request)) {
                Log.w(TAG, "no client to ask for '$resourceId'")
                return null
            }
            val answer = mailbox.poll(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (answer == null) {
                Log.w(TAG, "the app did not answer for '$resourceId' within ${REQUEST_TIMEOUT_MS}ms")
                return null
            }
            val resource = answer.resource ?: return null

            // Two ranges of the same resource can be asked for at once - the TV fetches audio and
            // video independently - so the loser of that race closes its descriptor and uses the
            // winner's, rather than leaving two open on the same file.
            val existing = cache.putIfAbsent(resourceId, resource)
            return if (existing != null) {
                resource.close()
                existing
            } else {
                resource
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            waiting.remove(requestId)
        }
    }

    /** Hands a `MSG_RESOURCE_RESPONSE` to whichever [resolve] is waiting for it. */
    fun onResponse(data: Bundle?) {
        val requestId = data?.getInt(CastContract.KEY_REQUEST_ID) ?: return
        @Suppress("DEPRECATION")
        val descriptor = data.getParcelable(CastContract.KEY_RESOURCE_FD) as? ParcelFileDescriptor

        val mailbox = waiting[requestId]
        if (mailbox == null) {
            // Already timed out or the session ended. The descriptor was duplicated into this
            // process by the Binder transaction, so dropping it silently would leak a file
            // descriptor per late answer.
            runCatching { descriptor?.close() }
            return
        }

        val resource = descriptor?.let {
            DescriptorResource(
                descriptor = it,
                // A negative length is the app saying it is still writing this and does not know
                // the final size, which is passed through rather than corrected: the proxy serves
                // it without a `Content-Length` and reads it as it grows.
                length = data.getLong(CastContract.KEY_RESOURCE_LENGTH),
                contentType = data.getString(CastContract.KEY_RESOURCE_TYPE)
                    ?: "application/octet-stream",
            )
        }
        if (!mailbox.offer(Answer(resource))) resource?.close()
    }

    /**
     * Records that a resource offered with an unknown length has finished being written.
     *
     * This is what releases a reader parked at the end of a growing file: without it the only way
     * out is the wait bound, which would report a complete track as truncated. A message with no
     * length means the producer failed, and readers are released with an error instead - the
     * failure has to travel, because a transcode that died leaves a file that simply stops growing
     * and looks exactly like one that is briefly behind.
     */
    fun onComplete(data: Bundle?) {
        val resourceId = data?.getString(CastContract.KEY_RESOURCE_ID) ?: return
        val resource = cache[resourceId]
        if (resource == null) {
            Log.w(TAG, "'$resourceId' was completed but was never handed over")
            return
        }
        if (data.containsKey(CastContract.KEY_RESOURCE_LENGTH)) {
            val length = data.getLong(CastContract.KEY_RESOURCE_LENGTH)
            resource.complete(length)
            Log.i(TAG, "'$resourceId' finished at $length bytes")
        } else {
            resource.fail()
            Log.w(TAG, "the app could not finish producing '$resourceId'")
        }
    }

    /** Closes every descriptor the app handed over. Called when the session ends. */
    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
        waiting.clear()
    }

    /**
     * One of the app's files, read at offsets.
     *
     * Reads are positional rather than seek-then-read. A descriptor duplicated across Binder shares
     * its file offset with the app's original, so moving the offset would move the app's too, and
     * two ranges being served at once would each read from where the other left off.
     * [FileChannel.read] with an explicit position touches neither.
     *
     * A resource may also still be being written, in which case its length is negative until the
     * app says otherwise. The end of such a file is not the end of the resource, so reads on it
     * wait rather than report one - see [GrowingStream].
     */
    private class DescriptorResource(
        descriptor: ParcelFileDescriptor,
        length: Long,
        override val contentType: String,
    ) : MediaResource {

        private val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        private val channel: FileChannel = stream.channel

        /**
         * Volatile, and no longer fixed at construction: the app can finish writing a resource
         * mid-session. Written from the service's main looper and read by every HTTP thread
         * serving it, which is exactly the case a non-volatile field gets wrong.
         */
        @Volatile
        override var length: Long = length
            private set

        /** Set when the app said it could not finish, so waiting readers fail instead of hanging. */
        @Volatile
        var hasFailed: Boolean = false
            private set

        /**
         * A growing resource gets a stream that waits; a finished one gets a plain positional read.
         *
         * Decided per [open] rather than once, so a request arriving after the transcode finished
         * takes the ordinary path - and gets a `Content-Length` and working seeks with it.
         */
        override fun open(offset: Long): InputStream =
            if (hasKnownLength) {
                PositionalStream(channel, offset)
            } else {
                GrowingStream(this, channel, offset)
            }

        fun complete(finalLength: Long) {
            length = finalLength
        }

        fun fail() {
            hasFailed = true
        }

        fun close() {
            // Closes the descriptor with it, which is the point of AutoCloseInputStream.
            runCatching { stream.close() }
        }
    }

    private class PositionalStream(
        private val channel: FileChannel,
        private var position: Long,
    ) : InputStream() {

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val read = channel.read(ByteBuffer.wrap(b, off, len), position)
            if (read > 0) position += read
            return read
        }

        /**
         * Deliberately not closing the channel: it belongs to the resource and answers every range
         * of it, so closing it here would break the next request rather than this one.
         */
        override fun close() = Unit
    }

    /**
     * Reads a file that is still being written, waiting at the end instead of reporting it.
     *
     * The problem this solves has no solution at the file layer: a descriptor to a growing file
     * reports EOF at whatever the current end of file is, and nothing distinguishes "the producer
     * has not got here yet" from "there is no more". So the answer comes from the producer instead,
     * and until it arrives a read that catches up simply waits.
     *
     * Three ways out, and each has to be its own: more bytes appear and are returned; the app says
     * it finished, and the real end of file becomes the end of the stream; or the app says it
     * failed, or says nothing at all for [GROWING_READ_TIMEOUT_MS], and the read throws - which the
     * proxy turns into a closed connection and a short body rather than a stall.
     */
    private class GrowingStream(
        private val resource: DescriptorResource,
        private val channel: FileChannel,
        private var position: Long,
    ) : InputStream() {

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val deadline = System.currentTimeMillis() + GROWING_READ_TIMEOUT_MS
            while (true) {
                val read = channel.read(ByteBuffer.wrap(b, off, len), position)
                if (read > 0) {
                    position += read
                    return read
                }

                // At the current end of file, so the producer's own state decides what that means.
                if (resource.hasFailed) throw IOException("the app failed to produce this resource")
                if (resource.hasKnownLength) {
                    // Read once more before believing it: the last bytes and the completion travel
                    // by different routes, so they can land in either order, and trusting the flag
                    // alone would drop whatever arrived in between.
                    val last = channel.read(ByteBuffer.wrap(b, off, len), position)
                    if (last > 0) {
                        position += last
                        return last
                    }
                    return -1
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw IOException("no new bytes for ${GROWING_READ_TIMEOUT_MS}ms at $position")
                }
                try {
                    Thread.sleep(GROWING_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted waiting for more of this resource", e)
                }
            }
        }

        /** The channel is the resource's, exactly as in [PositionalStream]. */
        override fun close() = Unit
    }
}
