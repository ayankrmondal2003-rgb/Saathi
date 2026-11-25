package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Always-visible emergency call during an SOS. Maitri never replaces 112: this works with no server, no account
 * and no network sync. It opens the dialer with 112 filled in (ACTION_DIAL needs no CALL_PHONE permission).
 */
@Composable
fun Call112Button(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val p = maitriPalette()
    ExtendedFloatingActionButton(
        onClick = {
            try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, tr("No phone app found. Dial 112."), Toast.LENGTH_LONG).show() }
        },
        modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = tr("Call 112, emergency services") },
        shape = RoundedCornerShape(28.dp),
        containerColor = p.accent,
        contentColor = p.onAccent,
        icon = { Icon(Icons.Rounded.Call, contentDescription = null) },
        text = { Text(tr("Call 112"), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    )
}
