package `in`.maitri.app.ui

import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.draw.drawWithContent

import `in`.maitri.app.tr

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import java.util.Locale

internal object MaitriLocale {
    private const val FILE = "maitri_language_prefs"
    private const val KEY = "selected_language"

    fun code(context: Context): String {
        val platform = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language else null
        val saved = platform ?: context.getSharedPreferences(FILE, 0).getString(KEY, "en")
        return MAITRI_LANGUAGES.firstOrNull { it.code == saved }?.code ?: "en"
    }

    fun localized(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(code(context)))
        return context.createConfigurationContext(config)
    }

    fun save(context: Context, code: String) {
        require(MAITRI_LANGUAGES.any { it.code == code })
        context.getSharedPreferences(FILE, 0).edit().putString(KEY, code).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(code)
        }
        // After the platform locale changes, so tr() reads the new language.
        `in`.maitri.app.I18n.init(context)
    }
}

@Composable
internal fun settingsLanguageCode(): String =
    if (LocalInspectionMode.current) "en" else MaitriLocale.code(LocalContext.current)

@Composable
internal fun settingsResources(): android.content.res.Resources {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    if (LocalInspectionMode.current) return context.resources
    val code = settingsLanguageCode()
    return remember(context, config, code) { MaitriLocale.localized(context).resources }
}

/** The reference boards are 390 units wide and reserve 28 units for the system status bar. */
@Composable
internal fun SettingsBoard(art: Int, height: Int, modifier: Modifier = Modifier, gapAt: Int? = null, gapHeight: Int = 0,
                           footer: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val p = maitriPalette()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(28.dp)
    BoxWithConstraints(modifier.fillMaxSize().background(p.background).padding(top = topInset).navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter) {
        val width = maxWidth.coerceAtMost(520.dp)
        val density = LocalDensity.current
        Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * width.value / 390f, density.fontScale)) {
                Box(Modifier.fillMaxWidth().height((height + if (gapAt != null) gapHeight else 0).dp)) {
                    if (gapAt == null) Image(painterResource(art), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    else {
                        // The artwork drawn twice: above the cut as is, below it moved down by the gap.
                        val painter = painterResource(art)
                        val cut = (gapAt - 28).dp
                        Image(painter, null, Modifier.fillMaxWidth().height(height.dp).drawWithContent {
                            clipRect(bottom = cut.toPx()) { this@drawWithContent.drawContent() } }, contentScale = ContentScale.FillBounds)
                        Image(painter, null, Modifier.offset(y = gapHeight.dp).fillMaxWidth().height(height.dp).drawWithContent {
                            clipRect(top = cut.toPx()) { this@drawWithContent.drawContent() } }, contentScale = ContentScale.FillBounds)
                    }
                    content()
                }
                footer()
            }
        }
    }
}

internal fun settingsSlot(x: Int, y: Int, width: Int, height: Int) =
    Modifier.offset(x.dp, (y - 28).dp).size(width.dp, height.dp)

@Composable
internal fun SettingsText(label: String, x: Int, y: Int, width: Int, height: Int, size: Int,
                          color: Color, bold: Boolean = false, center: Boolean = false,
                          line: Int = size + 2, maxLines: Int = 2, action: (() -> Unit)? = null) {
    val modifier = settingsSlot(x, y, width, height)
    Box(if (action == null) modifier else modifier.clickable(role = Role.Button, onClick = action),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        FitText(label, Modifier, color, size.toFloat(), line.toFloat(),
            if (bold) FontWeight.SemiBold else FontWeight.Normal, if (center) TextAlign.Center else TextAlign.Start, maxLines)
    }
}

@Composable
internal fun SettingsHeader(title: String, parent: String, back: String, onBack: () -> Unit) {
    val p = maitriPalette()
    BoardAction(24, 64, 44, 48, back, onClick = onBack)
    SettingsText(parent.uppercase(), 84, 57, 278, 22, 10, p.accent, true)
    SettingsText(title, 84, 79, 282, 37, 25, p.primary, true)
}
