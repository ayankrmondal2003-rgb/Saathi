package `in`.maitri.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * P7: every tr("…") text in the app has a translation in each language, with the same {0} placeholders.
 * Regenerate with: python tools/i18n.py keys && python tools/i18n.py merge
 */
class I18nTest {
    private val languages = listOf("hi", "bn", "mr", "ta", "te")
    private val assets = File("src/main/assets/i18n")

    private fun unescape(kotlin: String): String = buildString {
        var i = 0
        while (i < kotlin.length) {
            val c = kotlin[i]
            if (c == '\\' && i + 1 < kotlin.length) {
                when (val n = kotlin[i + 1]) {
                    'n' -> append('\n'); 't' -> append('\t'); 'u' -> { append(kotlin.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> append(n)
                }
                i += 2
            } else { append(c); i++ }
        }
    }

    private fun sourceTexts(): Set<String> {
        val call = Regex("""\b(?:tr|trKey)\(\s*"((?:[^"\\]|\\.)*)"""")
        return File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> call.findAll(file.readText()).map { unescape(it.groupValues[1]) } }.toSet()
    }

    private fun table(code: String): JsonObject = JsonParser.parseString(File(assets, "$code.json").readText()).asJsonObject

    @Test fun everyTextInTheCodeIsTranslatedInEveryLanguage() {
        val texts = sourceTexts()
        assertTrue("expected hundreds of texts, found ${texts.size}", texts.size > 500)
        for (code in languages) {
            val t = table(code)
            val missing = texts.filter { !t.has(it) }
            assertTrue("$code is missing ${missing.size}: ${missing.take(3)}", missing.isEmpty())
        }
    }

    @Test fun placeholdersSurviveTranslation() {
        val placeholder = Regex("""\{\d+\}""")
        for (code in languages) {
            val t = table(code)
            for (en in t.keySet()) {
                if (en.startsWith("_")) continue
                assertEquals("$code: $en", placeholder.findAll(en).map { it.value }.sorted().toList(),
                    placeholder.findAll(t.get(en).asString).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun eachFileSaysItNeedsReview() {
        languages.forEach { assertTrue(it, table(it).get("_review").asString.contains("review")) }
    }

    @Test fun formatFillsPositionalArgumentsInAnyOrder() {
        assertEquals("2 of 5 documents uploaded", tr("{0} of {1} documents uploaded", 2, 5))
        assertEquals("5 में से 2", I18n.format("{1} में से {0}", arrayOf(2, 5)))
        assertEquals("Plain", tr("Plain"))  // English when no language is set (JVM tests)
    }
}
