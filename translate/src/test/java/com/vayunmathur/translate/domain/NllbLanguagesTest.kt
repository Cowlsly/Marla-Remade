package com.vayunmathur.translate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The NLLB language-table contract, pinned in Kotlin so a drift between the picker and
 * the native token range fails here rather than as a silent mistranslation.
 *
 * The ground truth is the cached `facebook/nllb-200-distilled-600M` snapshot's
 * `tokenizer.json`: 202 flores200 codes at exactly 256001..256202, then `<mask>`.
 * Native asserts the same range in `post::translate::is_nllb_lang_token`; this asserts
 * the Kotlin side of it without needing the model on disk.
 */
class NllbLanguagesTest {

    @Test
    fun `all 202 flores languages are offered as targets`() {
        assertEquals(202, Languages.ALL.size, "flores200 has 202 languages")
        assertEquals(202, Languages.TARGETS.size)
    }

    @Test
    fun `sources are the targets plus the auto sentinel`() {
        assertEquals(203, Languages.SOURCES.size)
        assertEquals(Languages.AUTO, Languages.SOURCES.first())
        assertTrue(Languages.AUTO !in Languages.TARGETS, "auto is never a target")
    }

    @Test
    fun `every language has a distinct code and a distinct flores code`() {
        val codes = Languages.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "UI codes must be unique")
        val flores = Languages.ALL.map { it.flores }
        assertEquals(flores.size, flores.toSet().size, "flores codes must be unique")
    }

    @Test
    fun `every flores code is well formed`() {
        for (lang in Languages.ALL) {
            val parts = lang.flores.split("_")
            assertEquals(2, parts.size, "${lang.flores} is not <iso>_<Script>")
            assertEquals(3, parts[0].length, "${lang.flores} iso is not 3 letters")
            assertTrue(parts[1][0].isUpperCase(), "${lang.flores} script is not Titlecase")
        }
    }

    @Test
    fun `ui codes are valid BCP-47 for the TTS and speech paths`() {
        // TtsSpeaker and SpeechRecognizerEngine both feed these to
        // Locale.forLanguageTag, which returns a language-less Locale for garbage.
        for (lang in Languages.ALL) {
            val locale = java.util.Locale.forLanguageTag(lang.code)
            assertTrue(
                locale.language.isNotEmpty(),
                "${lang.code} is not a usable BCP-47 tag",
            )
        }
    }

    @Test
    fun `legacy stored codes still resolve instead of resetting to English`() {
        assertEquals("zh-Hans", Languages.byCode("zh").code)
        assertEquals("nso", Languages.byCode("ns").code)
        assertEquals("nb", Languages.byCode("no").code)
    }

    @Test
    fun `unknown codes fall back to auto then English rather than crashing`() {
        assertEquals(Languages.AUTO, Languages.byCode("auto"))
        assertEquals("en", Languages.byCode("xx").code)
    }

    @Test
    fun `the auto sentinel names the english token the translator falls back to`() {
        assertEquals("eng_Latn", Languages.AUTO.flores)
        assertEquals("eng_Latn", Languages.byCode("en").flores)
    }
}
