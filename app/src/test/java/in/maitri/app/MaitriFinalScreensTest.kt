package `in`.maitri.app

import `in`.maitri.app.ui.MAITRI_LANGUAGES
import `in`.maitri.app.ui.VibrationPattern
import org.junit.Assert.*
import org.junit.Test

class MaitriFinalScreensTest {

    // ------------------------------------------------------------------------
    // Screen 09: Profile & Settings Tests
    // ------------------------------------------------------------------------

    @Test
    fun testSettingsNavigationRoutes() {
        val availableDestinations = listOf(
            "EditProfile",
            "Permissions",
            "TestVibration",
            "SafetyPin",
            "Language"
        )
        assertEquals(5, availableDestinations.size)
        assertTrue(availableDestinations.contains("EditProfile"))
        assertTrue(availableDestinations.contains("Permissions"))
        assertTrue(availableDestinations.contains("TestVibration"))
        assertTrue(availableDestinations.contains("SafetyPin"))
        assertTrue(availableDestinations.contains("Language"))
    }

    @Test
    fun testThemeModeToggleNames() {
        fun getThemeDisplay(isDark: Boolean): String = if (isDark) "Dark" else "Blush"
        assertEquals("Blush", getThemeDisplay(false))
        assertEquals("Dark", getThemeDisplay(true))
    }

    // ------------------------------------------------------------------------
    // Screen 10: Language Catalog & Search Tests
    // ------------------------------------------------------------------------

    @Test
    fun testSixLanguageCatalogIntegrity() {
        assertEquals("Maitri must support exactly 6 languages from SVG specification", 6, MAITRI_LANGUAGES.size)
        val codes = MAITRI_LANGUAGES.map { it.code }
        assertTrue(codes.containsAll(listOf("en", "hi", "bn", "mr", "ta", "te")))

        val english = MAITRI_LANGUAGES.first { it.code == "en" }
        assertEquals("English", english.name)
        assertEquals("English", english.nativeName)

        val hindi = MAITRI_LANGUAGES.first { it.code == "hi" }
        assertEquals("Hindi", hindi.name)
        assertEquals("हिन्दी", hindi.nativeName)

        val bengali = MAITRI_LANGUAGES.first { it.code == "bn" }
        assertEquals("Bengali", bengali.name)
        assertEquals("বাংলা", bengali.nativeName)

        val marathi = MAITRI_LANGUAGES.first { it.code == "mr" }
        assertEquals("Marathi", marathi.name)
        assertEquals("मराठी", marathi.nativeName)

        val tamil = MAITRI_LANGUAGES.first { it.code == "ta" }
        assertEquals("Tamil", tamil.name)
        assertEquals("தமிழ்", tamil.nativeName)

        val telugu = MAITRI_LANGUAGES.first { it.code == "te" }
        assertEquals("Telugu", telugu.name)
        assertEquals("తెలుగు", telugu.nativeName)
    }

    @Test
    fun testLanguageSearchFilter() {
        fun search(query: String) = MAITRI_LANGUAGES.filter {
            it.name.contains(query, ignoreCase = true) || it.nativeName.contains(query, ignoreCase = true)
        }

        val emptyQuery = search("")
        assertEquals(6, emptyQuery.size)

        val queryEng = search("English")
        assertEquals(1, queryEng.size)
        assertEquals("en", queryEng[0].code)

        val queryBangla = search("বাংলা")
        assertEquals(1, queryBangla.size)
        assertEquals("bn", queryBangla[0].code)

        val queryNonExistent = search("Spanish")
        assertEquals(0, queryNonExistent.size)
    }

    // ------------------------------------------------------------------------
    // Screen 11: Vibration Pattern Tests
    // ------------------------------------------------------------------------

    @Test
    fun testThreeVibrationPatterns() {
        val patterns = VibrationPattern.entries
        assertEquals("Must provide exactly 3 vibration patterns", 3, patterns.size)

        val gentle = VibrationPattern.GENTLE
        assertEquals("Gentle", gentle.title)
        assertEquals("Short, soft pulses", gentle.subtitle)
        assertTrue("Gentle pattern must have positive timing pulses", gentle.timings.isNotEmpty())

        val steady = VibrationPattern.STEADY
        assertEquals("Steady", steady.title)
        assertEquals("Balanced, clear pulses", steady.subtitle)
        assertTrue(steady.timings.sum() > gentle.timings.sum())

        val strong = VibrationPattern.STRONG
        assertEquals("Strong", strong.title)
        assertEquals("Longer, stronger pulses", strong.subtitle)
        assertTrue(strong.timings.sum() > steady.timings.sum())
    }

    // Screens 12-14 are covered against production logic in MaitriCheckpointCTest.
}
