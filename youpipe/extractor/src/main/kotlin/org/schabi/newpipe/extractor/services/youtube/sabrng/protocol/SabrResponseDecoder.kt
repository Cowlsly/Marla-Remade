package org.schabi.newpipe.extractor.services.youtube.sabrng.protocol

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrResponse
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrFormatInitializationMetadata
import org.schabi.newpipe.extractor.services.youtube.sabrng.generated.SabrMediaHeader

/** Decodes UMP parts of a SABR response into structured control state on [YoutubeSabrResponse]. */
object SabrResponseDecoder {
    const val ONESIE_HEADER = 10
    const val ONESIE_DATA = 11
    const val ONESIE_ENCRYPTED_MEDIA = 12
    const val MEDIA_HEADER = 20
    const val MEDIA = 21
    const val MEDIA_END = 22
    const val CONFIG = 30
    const val LIVE_METADATA = 31
    const val HOSTNAME_CHANGE_HINT_DEPRECATED = 32
    const val LIVE_METADATA_PROMISE = 33
    const val LIVE_METADATA_PROMISE_CANCELLATION = 34
    const val NEXT_REQUEST_POLICY = 35
    const val USTREAMER_VIDEO_AND_FORMAT_METADATA = 36
    const val FORMAT_SELECTION_CONFIG = 37
    const val USTREAMER_SELECTED_MEDIA_STREAM = 38
    const val FORMAT_INITIALIZATION_METADATA = 42
    const val SABR_REDIRECT = 43
    const val SABR_ERROR = 44
    const val SABR_SEEK = 45
    const val RELOAD_PLAYER_RESPONSE = 46
    const val PLAYBACK_START_POLICY = 47
    const val ALLOWED_CACHED_FORMATS = 48
    const val START_BW_SAMPLING_HINT = 49
    const val PAUSE_BW_SAMPLING_HINT = 50
    const val SELECTABLE_FORMATS = 51
    const val REQUEST_IDENTIFIER = 52
    const val REQUEST_CANCELLATION_POLICY = 53
    const val ONESIE_PREFETCH_REJECTION = 54
    const val TIMELINE_CONTEXT = 55
    const val REQUEST_PIPELINING = 56
    const val SABR_CONTEXT_UPDATE = 57
    const val STREAM_PROTECTION_STATUS = 58
    const val SABR_CONTEXT_SENDING_POLICY = 59
    const val LAWNMOWER_POLICY = 60
    const val SABR_ACK = 61
    const val END_OF_TRACK = 62
    const val CACHE_LOAD_POLICY = 63
    const val LAWNMOWER_MESSAGING_POLICY = 64
    const val PREWARM_CONNECTION = 65
    const val PLAYBACK_DEBUG_INFO = 66
    const val SNACKBAR_MESSAGE = 67

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun decode(data: ByteArray): YoutubeSabrResponse = decodeParts(UmpReader.readAll(data))

    /**
     * Decode an already-parsed list of UMP parts. Used by the streaming path, which collects the
     * small control parts (everything except the big MEDIA payloads) and decodes them here, while
     * the MEDIA segments are assembled separately so the whole body is never held at once.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun decodeParts(parts: List<UmpReader.UmpPart>): YoutubeSabrResponse {
        val decoded = YoutubeSabrResponse()
        for (part in parts) {
            val partData = part.getRawData()
            decoded.addPart(part)
            if (part.getType() != MEDIA && part.getType() != MEDIA_END) {
                try {
                    decoded.addWireFieldSummary(part.getType(), SabrProto.summarizeFields(partData))
                } catch (ignored: SabrProtocolException) {
                    decoded.addWireFieldSummary(part.getType(), "opaqueBytes=" + partData.size)
                }
            }
            try {
                if (part.getType() == MEDIA_HEADER) {
                    decoded.addMediaHeader(SabrMediaHeader.decode(partData))
                    continue
                }
                if (part.getType() == MEDIA) {
                    if (partData.isNotEmpty()) {
                        decoded.addMediaBytes(partData[0].toInt() and 0xff, partData.size - 1L)
                    }
                    continue
                }
                if (part.getType() == MEDIA_END) {
                    if (partData.isNotEmpty()) decoded.addMediaEndHeaderId(partData[0].toInt() and 0xff)
                    continue
                }
                when (part.getType()) {
                    ONESIE_HEADER, ONESIE_DATA, ONESIE_ENCRYPTED_MEDIA ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    FORMAT_INITIALIZATION_METADATA -> {
                        val metadata = SabrFormatInitializationMetadata.decode(partData)
                        decoded.addFormatInitializationMetadata(metadata)
                        decoded.addGenericPartDescription(part.getType(), metadata.summarize())
                    }
                    MEDIA_HEADER -> decoded.addMediaHeader(SabrMediaHeader.decode(partData))
                    MEDIA -> if (partData.isNotEmpty()) {
                        decoded.addMediaBytes(partData[0].toInt() and 0xff, partData.size - 1L)
                    }
                    MEDIA_END -> if (partData.isNotEmpty()) {
                        decoded.addMediaEndHeaderId(partData[0].toInt() and 0xff)
                    }
                    LIVE_METADATA -> {
                        decoded.addLiveMetadata(partData)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    NEXT_REQUEST_POLICY -> {
                        decodeNextRequestPolicy(partData, decoded)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    SABR_REDIRECT -> {
                        decoded.setRedirectUrl(readString(partData, 1))
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    SABR_SEEK ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    SABR_ERROR -> {
                        val error = decodeError(partData)
                        decoded.setSabrError(error)
                        decoded.addGenericPartDescription(part.getType(), error)
                    }
                    RELOAD_PLAYER_RESPONSE -> {
                        decoded.setReloadRequested(true)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    STREAM_PROTECTION_STATUS -> {
                        decodeStreamProtectionStatus(partData, decoded)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    PLAYBACK_START_POLICY ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    SABR_CONTEXT_UPDATE -> {
                        decoded.addSabrContextUpdate(partData)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    SABR_CONTEXT_SENDING_POLICY -> {
                        decoded.setSabrContextSendingPolicy(partData)
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                    SNACKBAR_MESSAGE ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    FORMAT_SELECTION_CONFIG ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    PREWARM_CONNECTION ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    START_BW_SAMPLING_HINT, CONFIG, HOSTNAME_CHANGE_HINT_DEPRECATED,
                    LIVE_METADATA_PROMISE, LIVE_METADATA_PROMISE_CANCELLATION,
                    USTREAMER_VIDEO_AND_FORMAT_METADATA, USTREAMER_SELECTED_MEDIA_STREAM,
                    ALLOWED_CACHED_FORMATS, PAUSE_BW_SAMPLING_HINT, ONESIE_PREFETCH_REJECTION,
                    TIMELINE_CONTEXT, REQUEST_PIPELINING, LAWNMOWER_POLICY, SABR_ACK, END_OF_TRACK,
                    CACHE_LOAD_POLICY, LAWNMOWER_MESSAGING_POLICY, PLAYBACK_DEBUG_INFO ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    REQUEST_IDENTIFIER ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    REQUEST_CANCELLATION_POLICY ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    SELECTABLE_FORMATS ->
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    else -> {
                        decoded.addUnknownPartType(part.getType())
                        decoded.addGenericPartDescription(part.getType(), describeGenericMessage(partData))
                    }
                }
            } catch (e: SabrProtocolException) {
                // One malformed protobuf message must not discard valid MEDIA from the rest of the
                // UMP response. Ignore only that part and retain a bounded diagnostic.
                decoded.addMalformedPart(part.getType(), part.getSize(), e)
            }
        }
        return decoded
    }

    @Throws(SabrProtocolException::class)
    private fun decodeNextRequestPolicy(data: ByteArray, decoded: YoutubeSabrResponse) {
        decoded.setNextRequestPolicy(data)
        for (field in SabrProto.readFields(data)) {
            if (field.getNumber() == 4 && field.getWireType() == SabrProto.WIRE_VARINT) {
                decoded.setBackoffTimeMs(field.getVarint().toInt())
            }
        }
    }

    @Throws(SabrProtocolException::class)
    private fun decodeStreamProtectionStatus(data: ByteArray, decoded: YoutubeSabrResponse) {
        for (field in SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                decoded.setStreamProtectionStatus(field.getVarint().toInt())
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                decoded.setStreamProtectionMaxRetries(field.getVarint().toInt())
            }
        }
    }

    @Throws(SabrProtocolException::class)
    private fun readString(data: ByteArray, number: Int): String? {
        var value: String? = null
        for (field in SabrProto.readFields(data)) {
            if (field.getNumber() == number &&
                field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED
            ) {
                value = field.getString()
            }
        }
        return value
    }

    @Throws(SabrProtocolException::class)
    private fun decodeError(data: ByteArray): String {
        var type: String? = null
        var code = 0
        for (field in SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                type = field.getString()
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                code = field.getVarint().toInt()
            }
        }
        return "type=" + (type ?: "null") + ", code=" + code
    }

    private fun describeGenericMessage(data: ByteArray): String {
        return try {
            SabrProto.summarizeFields(data)
        } catch (e: Exception) {
            "undecodable(" + data.size + " bytes)"
        }
    }
}
