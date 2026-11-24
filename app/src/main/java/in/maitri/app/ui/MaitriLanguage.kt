package `in`.maitri.app.ui

import `in`.maitri.app.R
import `in`.maitri.app.R.string as S
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LanguageOption(
    val code: String,
    val name: String,
    val nativeName: String
)

val MAITRI_LANGUAGES = listOf(
    LanguageOption("en", "English", "English"),
    LanguageOption("hi", "Hindi", "हिन्दी"),
    LanguageOption("bn", "Bengali", "বাংলা"),
    LanguageOption("mr", "Marathi", "मराठी"),
    LanguageOption("ta", "Tamil", "தமிழ்"),
    LanguageOption("te", "Telugu", "తెలుగు")
)

/**
 * Screen 10 — Language Selection.
 * Strict SVG fidelity matching design-reference/crops/light-10.svg and dark-10.svg.
 */
@Composable
fun MaitriLanguage(
    onBack: () -> Unit,
    onLanguageSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val resources = settingsResources()
    val language = java.util.Locale.forLanguageTag(settingsLanguageCode())
    var selectedCode by rememberSaveable { mutableStateOf(language.language) }
    var search by rememberSaveable { mutableStateOf("") }
    val names = remember(language) { MAITRI_LANGUAGES.associate { option ->
        option.code to if (language.language == "en") option.name else
            java.util.Locale.forLanguageTag(option.code).getDisplayLanguage(language).replaceFirstChar { it.titlecase(language) }
    } }
    val matches = MAITRI_LANGUAGES.filter { option ->
        listOf(option.name, option.nativeName, names.getValue(option.code)).any { it.contains(search.trim(), ignoreCase = true) }
    }
    SettingsBoard(if (p.dark) R.drawable.maitri_dark_10 else R.drawable.maitri_light_10, 788, modifier) {
        SettingsHeader(resources.getString(S.settings_language), resources.getString(S.settings_profile), resources.getString(S.settings_back), onBack)
        SettingsText(resources.getString(S.settings_lang_banner), 40, 155, 310, 33, 20, p.primary, true)
        SettingsText(resources.getString(S.settings_lang_hint), 40, 188, 310, 29, 12, p.secondary)
        Box(settingsSlot(70, 252, 278, 48), contentAlignment = Alignment.CenterStart) {
            BasicTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(fontFamily = `in`.maitri.app.ui.theme.MaitriFont, color = p.primary, fontSize = 13.sp),
                decorationBox = { inner ->
                    Box {
                        if (search.isEmpty()) Text(resources.getString(S.settings_search), color = p.secondary, fontSize = 13.sp)
                        inner()
                    }
                })
        }
        Column(settingsSlot(24, 320, 342, 336).clip(RoundedCornerShape(22.dp))) {
            matches.forEachIndexed { index, option ->
                val selected = selectedCode == option.code
                Box(Modifier.fillMaxWidth().height(56.dp).background(if (selected) p.card else p.surface)
                    .selectable(selected, role = Role.RadioButton, onClick = { selectedCode = option.code })) {
                    Text(names.getValue(option.code), Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                        color = p.primary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    if (selected) Text(resources.getString(S.settings_selected), Modifier.align(Alignment.CenterEnd).padding(end = 68.dp),
                        color = p.accent, fontSize = 10.sp)
                    PatternRadio(selected, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp), p)
                    if (index < matches.lastIndex) HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp), color = p.divider)
                }
            }
            if (matches.isEmpty()) Text(resources.getString(S.settings_empty), Modifier.padding(16.dp), color = p.secondary)
        }
        SettingsText(resources.getString(S.settings_lang_note), 24, 685, 342, 38, 11, p.secondary, center = true)
        SettingsText(resources.getString(S.settings_save), 24, 736, 342, 52, 15, if (p.dark) p.background else Color.White, true, true) {
            MaitriLocale.save(context, selectedCode)
            onLanguageSelected(selectedCode)
            onBack()
        }
    }
}
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriLanguageLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriLanguage(onBack = {})
    }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriLanguageDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriLanguage(onBack = {})
    }
}
