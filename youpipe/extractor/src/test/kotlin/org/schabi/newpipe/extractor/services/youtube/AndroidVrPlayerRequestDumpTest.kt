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
 * Offline dump + regression test for the signed-out ANDROID_VR /player request.
 *
 * This constructs youpipe's real android_vr request body via
 * [YoutubeStreamHelper.buildAndroidVrPlayerRequestBody] (the exact same function used by
 * [YoutubeStreamHelper.getAndroidVrPlayerResponse]) for a fixed videoId, then prints the HTTP
 * method, full URL, all headers and the complete request body JSON so it can be compared
 * byte-for-byte with PipePipe's android_vr request.
 *
 * signatureTimestamp is the only value that would otherwise require a network/JS fetch; it is
 * pinned to a fixed placeholder here purely so the dump is deterministic and offline.
 */
class AndroidVrPlayerRequestDumpTest {

    private val videoId = "dQw4w9WgXcQ"
    private val cpn = "abcdef_1234567890AB"
    private val signatureTimestamp = 20073
    private val localization = Localization.DEFAULT
    private val contentCountry = ContentCountry.DEFAULT

    private fun body(): JsonObject = YoutubeStreamHelper.buildAndroidVrPlayerRequestBody(
        localization, contentCountry, videoId, cpn, signatureTimestamp
    )

    @Test
    fun dumpAndroidVrRequest() {
        val bodyJson = body()
        val bodyBytes = bodyJson.toString().toByteArray(Charsets.UTF_8)
        val headers = YoutubeStreamHelper.getAndroidVrHeaders()
        val url = YoutubeStreamHelper.getAndroidVrPlayerUrl()

        val sb = StringBuilder()
        sb.appendLine("==================== ANDROID_VR /player REQUEST DUMP ====================")
        sb.appendLine("METHOD: POST")
        sb.appendLine("URL: $url")
        sb.appendLine("videoId (fixed): $videoId")
        sb.appendLine("signatureTimestamp (fixed placeholder): $signatureTimestamp")
        sb.appendLine("--- HEADERS ---")
        headers.toSortedMap().forEach { (name, values) ->
            values.forEach { sb.appendLine("$name: $it") }
        }
        sb.appendLine("--- BODY (compact, exactly what is sent, ${bodyBytes.size} bytes) ---")
        sb.appendLine(bodyJson.toString())
        sb.appendLine("--- BODY (pretty) ---")
        sb.appendLine(prettyPrint(bodyJson.toString()))
        sb.appendLine("========================================================================")
        println(sb.toString())
    }

    @Test
    fun bodyMatchesPipePipeShape() {
        val root = body()
        val client = root["context"]!!.jsonObject["client"]!!.jsonObject

        // Signed-out android_vr body must NOT carry request/user/thirdParty objects
        // (PipePipe's fetchConfiguredJsonPlayer omits them).
        val context = root["context"]!!.jsonObject
        assertFalse(context.containsKey("request"), "context.request must be absent (matches PipePipe)")
        assertFalse(context.containsKey("user"), "context.user must be absent (matches PipePipe)")
        assertFalse(context.containsKey("thirdParty"), "context.thirdParty must be absent")

        // Signed-out: no visitorData and no poToken/serviceIntegrityDimensions.
        assertFalse(client.containsKey("visitorData"), "visitorData must be absent when signed out")
        assertFalse(
            root.containsKey("serviceIntegrityDimensions"),
            "serviceIntegrityDimensions must be absent when signed out"
        )

        // Client identity must match PipePipe's android_vr PlayerClient exactly.
        assertEquals("ANDROID_VR", client["clientName"]!!.toString().trim('"'))
        assertEquals("1.65.10", client["clientVersion"]!!.toString().trim('"'))
        assertEquals("Oculus", client["deviceMake"]!!.toString().trim('"'))
        assertEquals("Quest 3", client["deviceModel"]!!.toString().trim('"'))
        assertEquals("Android", client["osName"]!!.toString().trim('"'))
        assertEquals("12L", client["osVersion"]!!.toString().trim('"'))
        assertEquals("32", client["androidSdkVersion"]!!.toString())
        assertEquals("UTC", client["timeZone"]!!.toString().trim('"'))
        assertEquals("0", client["utcOffsetMinutes"]!!.toString())

        // playbackContext + top-level checks.
        val cpc = root["playbackContext"]!!.jsonObject["contentPlaybackContext"]!!.jsonObject
        assertEquals("HTML5_PREF_WANTS", cpc["html5Preference"]!!.toString().trim('"'))
        assertTrue(cpc.containsKey("signatureTimestamp"))
        assertEquals(videoId, root["videoId"]!!.toString().trim('"'))
        assertEquals(cpn, root["cpn"]!!.toString().trim('"'))
        assertEquals("true", root["contentCheckOk"]!!.toString())
        assertEquals("true", root["racyCheckOk"]!!.toString())
    }

    @Test
    fun bodyIsByteIdenticalToExpectedPipePipeJson() {
        // The exact compact JSON PipePipe's fetchConfiguredJsonPlayer produces for the signed-out
        // android_vr path with the same inputs (field order included).
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

        assertEquals(expected, body().toString())
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
