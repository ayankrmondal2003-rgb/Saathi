package `in`.maitri.app

import androidx.compose.ui.graphics.toArgb
import `in`.maitri.app.ui.MaitriPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The light palette in code and the colour resources used by the board artwork are the same tokens. */
class MaitriPaletteTokensTest {
    private fun resColor(name: String): Int {
        val xml = File("src/main/res/values/maitri_palette.xml").readText()
        val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(xml)!!.groupValues[1]
        return (0xFF000000 or hex.toLong(16)).toInt()
    }

    @Test fun codeAndResourcesAgree() {
        val light = MaitriPalette(false)
        assertEquals(resColor("maitri_blush"), light.card.toArgb())
        assertEquals(resColor("maitri_blush_subtle"), light.subtle.toArgb())
        assertEquals(resColor("maitri_secondary"), light.secondary.toArgb())
    }

    @Test fun lightBoardsUseTheTokensNotLiterals() {
        File("src/main/res/drawable").listFiles { f -> f.name.startsWith("maitri_light_") }!!.forEach { f ->
            val xml = f.readText()
            // Fills and strokes only: the logo tile's gradient (startColor/endColor) is logo artwork and keeps its colours.
            assertTrue("${f.name} has a literal blush", !Regex("(?i)(fill|stroke)Color=\"#FCE8ED\"").containsMatchIn(xml))
            assertTrue("${f.name} has a literal secondary", !Regex("(?i)(fill|stroke)Color=\"#7D6771\"").containsMatchIn(xml))
        }
    }
}
