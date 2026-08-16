package org.schabi.newpipe.extractor.services.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Offline dump + regression test for the signed-out /player requests.
 *
 * The DEFAULT signed-out client is VISIONOS, matching PipePipe's current default
 * (NewPipe.youtubePlayerClient = "visionos"). VISIONOS carries a valid visitorData, so YouTube
 * does not bot-flag it; the anonymous ANDROID_VR request has no visitorData and is now rejected
 * with SignInConfirmNotBotException (LOGIN_REQUIRED).
 *
 * This test reconstructs both requests offline for a fixed videoId and prints method, full URL,
 * all headers and the complete body JSON so they can be compared byte-for-byte with PipePipe.
 * Values that would otherwise require a network/JS fetch (visitorData, signatureTimestamp) are
 * pinned to fixed placeholders purely so the dump is deterministic and offline.
 */
class SignedOutPlayerRequestDumpTest {

    private val videoId = "dQw4w9WgXcQ"
    private val cpn = "abcdef_1234567890AB"
    private val signatureTimestamp = 20073
    private val visitorDataPlaceholder = "CgtプレースホルダーVISITOR"
    private val localization = Localization.DEFAULT
    private val contentCountry = ContentCountry.DEFAULT

    // ---- VISIONOS (primary / default signed-out path) ----

    private fun visionOsBody(): JsonObject {
        val info = InnertubeClientRequestInfo.ofVisionOsClient()
        info.clientInfo.visitorData = visitorDataPlaceholder
        val builder = YoutubeParsingHelper.prepareJsonBuilder(
            localization, contentCountry, info, null
        )
        builder.value(YoutubeParsingHelper.VIDEO_ID, videoId)
            .value(YoutubeParsingHelper.CPN, cpn)
            .value(YoutubeParsingHelper.CONTENT_CHECK_OK, true)
            .value(YoutubeParsingHelper.RACY_CHECK_OK, true)
        return builder.done()
    }

    @Test
    fun dumpVisionOsRequest() {
        val body = visionOsBody()
        val url = YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL + "player?" +
            YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER + "&t=<tParam>&id=$videoId"
        val sb = StringBuilder()
        sb.appendLine("============ VISIONOS /player REQUEST DUMP (DEFAULT signed-out) ============")
        sb.appendLine("METHOD: POST")
        sb.appendLine("URL: $url")
        sb.appendLine("videoId (fixed): $videoId")
        sb.appendLine("visitorData (fixed placeholder; real value fetched from gapis /visitor_id): $visitorDataPlaceholder")
        sb.appendLine("--- HEADERS ---")
        sb.appendLine("Content-Type: application/json")
        sb.appendLine("User-Agent: <visionOS UA from getVisionOsUserAgent(localization)>")
        sb.appendLine("X-Goog-Api-Format-Version: 2")
        sb.appendLine("--- BODY (compact) ---")
        sb.appendLine(body.toString())
        sb.appendLine("--- BODY (pretty) ---")
        sb.appendLine(prettyPrint(body.toString()))
        sb.appendLine("===========================================================================")
        println(sb.toString())
    }

    @Test
    fun visionOsBodyCarriesVisitorDataAndCorrectClient() {
        val root = visionOsBody()
        val client = root["context"]!!.jsonObject["client"]!!.jsonObject
        // The whole point: VISIONOS sends visitorData (this is why it is not bot-flagged).
        assertTrue(client.containsKey("visitorData"), "VISIONOS body MUST carry visitorData")
        assertEquals("VISIONOS", client["clientName"]!!.toString().trim('"'))
        assertEquals("1.02", client["clientVersion"]!!.toString().trim('"'))
        assertEquals("RealityDevice14,1", client["deviceModel"]!!.toString().trim('"'))
        assertEquals("visionOS", client["osName"]!!.toString().trim('"'))
        assertEquals(videoId, root["videoId"]!!.toString().trim('"'))
        assertEquals(cpn, root["cpn"]!!.toString().trim('"'))
    }

    // ---- ANDROID_VR (reference only; NOT used by default — bot-flagged when anonymous) ----

    private fun androidVrBody(): JsonObject = YoutubeStreamHelper.buildAndroidVrPlayerRequestBody(
        localization, contentCountry, videoId, cpn, signatureTimestamp
    )

    @Test
    fun dumpAndroidVrRequest() {
        val body = androidVrBody()
        val headers = YoutubeStreamHelper.getAndroidVrHeaders()
        val url = YoutubeStreamHelper.getAndroidVrPlayerUrl()
        val sb = StringBuilder()
        sb.appendLine("======= ANDROID_VR /player REQUEST DUMP (reference; not default) =======")
        sb.appendLine("METHOD: POST")
        sb.appendLine("URL: $url")
        sb.appendLine("NOTE: anonymous ANDROID_VR has NO visitorData => bot-flagged (LOGIN_REQUIRED).")
        sb.appendLine("--- HEADERS ---")
        headers.toSortedMap().forEach { (name, values) -> values.forEach { sb.appendLine("$name: $it") } }
        sb.appendLine("--- BODY (compact) ---")
        sb.appendLine(body.toString())
        sb.appendLine("--- BODY (pretty) ---")
        sb.appendLine(prettyPrint(body.toString()))
        sb.appendLine("=======================================================================")
        println(sb.toString())
    }

    @Test
    fun androidVrBodyIsByteIdenticalToPipePipe() {
        val expected =
            """{"context":{"client":{"utcOffsetMinutes":0,"timeZone":"UTC","hl":"${
                localization.getLocalizationCode()
            }","gl":"${
                contentCountry.countryCode
            }","userAgent":"${
                ClientsConstants.ANDROID_VR_USER_AGENT
            }","clientName":"ANDROID_VR","clientVersion":"1.65.10","deviceMake":"Oculus",""" +
                """"deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}},""" +
                """"playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS",""" +
                """"signatureTimestamp":$signatureTimestamp}},""" +
                """"cpn":"$cpn","videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
        assertEquals(expected, androidVrBody().toString())
        // Reference invariant: android_vr anonymous body has NO visitorData (the bot-flag cause).
        val client = androidVrBody()["context"]!!.jsonObject["client"]!!.jsonObject
        assertFalse(client.containsKey("visitorData"))
    }

    private fun prettyPrint(compact: String): String {
        val out = StringBuilder()
        var indent = 0
        var inString = false
        var escaped = false
        for (c in compact) {
            if (escaped) {
                out.append(c); escaped = false; continue
            }
            when (c) {
                '\\' -> { out.append(c); escaped = true }
                '"' -> { out.append(c); inString = !inString }
                '{', '[' -> if (inString) out.append(c) else {
                    out.append(c).append('\n'); indent++; out.append("  ".repeat(indent))
                }
                '}', ']' -> if (inString) out.append(c) else {
                    out.append('\n'); indent--; out.append("  ".repeat(indent)).append(c)
                }
                ',' -> if (inString) out.append(c) else {
                    out.append(c).append('\n').append("  ".repeat(indent))
                }
                ':' -> if (inString) out.append(c) else out.append(": ")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
