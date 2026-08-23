package com.vayunmathur.cast.service

import android.os.Bundle
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vayunmathur.cast.protocol.MediaResource
import com.vayunmathur.cast.protocol.MediaResourceResolver
import com.vayunmathur.sdk.cast.CastContract
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
                length = data.getLong(CastContract.KEY_RESOURCE_LENGTH),
                contentType = data.getString(CastContract.KEY_RESOURCE_TYPE)
                    ?: "application/octet-stream",
            )
        }
        if (!mailbox.offer(Answer(resource))) resource?.close()
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
     */
    private class DescriptorResource(
        descriptor: ParcelFileDescriptor,
        override val length: Long,
        override val contentType: String,
    ) : MediaResource {

        private val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        private val channel: FileChannel = stream.channel

        override fun open(offset: Long): InputStream = PositionalStream(channel, offset)

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
}
