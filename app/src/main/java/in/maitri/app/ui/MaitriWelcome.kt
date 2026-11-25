package `in`.maitri.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.R
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeMode

/** Screen 01. Original SVG branding with native, accessible controls. */
@Composable
fun MaitriWelcome(onGetStarted: () -> Unit) {
    val dark = when (LocalThemeController.current.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaitriWelcomeContent(dark, onGetStarted)
}

@Composable
internal fun MaitriWelcomeContent(dark: Boolean, onGetStarted: () -> Unit) {
    val background = Color(if (dark) 0xFF19151B else 0xFFFFF9FA)
    val accent = Color(if (dark) 0xFFFF9CBE else 0xFFB82055)
    val secondary = Color(if (dark) 0xFFC3ADBA else 0xFF7D6771)
    BoxWithConstraints(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        // Reference excludes the 44px status and 34px gesture areas. Small screens scroll.
        val contentHeight = maxOf(maxHeight, 766.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .heightIn(min = contentHeight).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(119.dp))
            Box(Modifier.size(292.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(MaitriPalette(dark).subtle, CircleShape))
                Box(Modifier.offset(y = 1.dp).size(242.dp).background(MaitriPalette(dark).card, CircleShape))
                Image(painterResource(R.drawable.maitri_logo), null, Modifier.offset(y = 1.dp).size(147.dp))
            }
            Spacer(Modifier.height(3.5.dp))
            Image(
                painterResource(if (dark) R.drawable.maitri_wordmark_dark else R.drawable.maitri_wordmark_light),
                stringResource(R.string.maitri_brand), Modifier.size(169.dp, 50.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.maitri_tagline), color = accent, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.maitri_supporting), color = secondary, fontSize = 16.sp,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(112.dp))
            Button(
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent,
                    contentColor = if (dark) background else Color.White),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                elevation = null
            ) {
                Text(stringResource(R.string.maitri_get_started), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.maitri_footer), color = secondary, fontSize = 10.sp,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Preview(name = "Maitri 01 Light", widthDp = 390, heightDp = 766)
@Composable
private fun MaitriWelcomeLightPreview() { MaitriWelcomeContent(false) {} }

@Preview(name = "Maitri 01 Dark", widthDp = 390, heightDp = 766)
@Composable
private fun MaitriWelcomeDarkPreview() { MaitriWelcomeContent(true) {} }
