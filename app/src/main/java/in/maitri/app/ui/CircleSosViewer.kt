package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.SosMediaItemDto
import `in`.maitri.app.network.Uploads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A circle member's view of someone's SOS: a button for the live location while it's active, and the voice notes
 * and photos they recorded. Media arrives as the sender's phone uploads it, so the list refreshes every 15 seconds.
 */
@Composable
internal fun CircleSosViewer(uid: String, incidentId: String, liveUrl: String?, dismiss: () -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val account = remember(uid) { ExpectedAccount(uid) }
    var media by remember { mutableStateOf<List<SosMediaItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    // The player is called explicitly (mp.stop()): inside `player?.runCatching { stop() }` a local function named
    // stop() wins over MediaPlayer.stop() and calls itself forever (that crashed the app on Stop).
    fun stopPlayback() {
        val mp = player[0]
        player[0] = null
        if (mp != null) { runCatching { mp.stop() }; runCatching { mp.release() } }
        playing = null
        preparing = null
    }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    LaunchedEffect(incidentId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try { media = BackendClient.files.incidentMedia(incidentId, account).items; error = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (media == null) error = tr("Couldn't load their voice notes and photos.") }
                delay(15_000)
            }
        }
    }

    suspend fun play(item: SosMediaItemDto) {
        stopPlayback()
        preparing = item.fileId
        val url = try { BackendClient.files.downloadUrl(item.fileId, account).downloadUrl }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (preparing != item.fileId) return  // stopped while the link was loading
        if (url == null) { preparing = null; error = tr("Couldn't play this voice note."); return }
        val mp = MediaPlayer()
        player[0] = mp
        runCatching {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(url)
            mp.setOnPreparedListener { if (player[0] === it) { preparing = null; playing = item.fileId; it.start() } }
            mp.setOnCompletionListener { if (player[0] === it) stopPlayback() }
            mp.setOnErrorListener { it2, _, _ -> if (player[0] === it2) { stopPlayback(); error = tr("Couldn't play this voice note.") }; true }
            mp.prepareAsync()
        }.onFailure { stopPlayback(); error = tr("Couldn't play this voice note.") }
    }

    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = dismiss, containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(if (liveUrl != null) tr("SOS from your circle") else tr("Their SOS")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (liveUrl != null) Button(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(liveUrl))) }
                }, colors = ButtonDefaults.buttonColors(containerColor = p.accent), modifier = Modifier.fillMaxWidth()) {
                    Text(tr("See live location"), fontWeight = FontWeight.SemiBold)
                } else Text(tr("This SOS has ended. Their location is no longer shared."))
                Text(tr("Voice notes and photos"), color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                error?.let { Text(it, color = p.accent, fontSize = 12.sp) }
                val list = media
                when {
                    list == null && error == null -> MaitriLoader(Modifier.fillMaxWidth(), logoSize = 40.dp)
                    list.isNullOrEmpty() -> Text(tr("Nothing yet. Anything they record during the SOS appears here."), fontSize = 12.sp)
                    else -> list.forEachIndexed { index, item ->
                        if (item.kind == "SOS_PHOTO") SosPhoto(account, item, p)
                        else VoiceRow(tr("Voice note {0}", list.take(index + 1).count { it.kind == "SOS_VOICE" }),
                            relativeTime(item.uploadedAt.orEmpty()), item.fileId == playing, item.fileId == preparing, p,
                            onToggle = { if (item.fileId == playing || item.fileId == preparing) stopPlayback() else play(item) })
                    }
                }
                Text(tr("Only their circle can see these. They're deleted 30 days after the SOS."), fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Close"), color = p.accent) } })
}

@Composable
private fun VoiceRow(title: String, at: String, playing: Boolean, preparing: Boolean, p: MaitriPalette, onToggle: suspend () -> Unit) {
    var tap by remember { mutableIntStateOf(0) }
    LaunchedEffect(tap) { if (tap > 0) onToggle() }
    Row(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (at.isNotEmpty()) Text(at, fontSize = 11.sp)
        }
        TextButton(onClick = { tap++ }) {
            Text(when { preparing -> tr("Loading…"); playing -> tr("Stop"); else -> tr("Play") }, color = p.accent)
        }
    }
}

@Composable
private fun SosPhoto(account: ExpectedAccount, item: SosMediaItemDto, p: MaitriPalette) {
    var image by remember(item.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(item.fileId) { mutableStateOf(false) }
    LaunchedEffect(item.fileId) {
        try {
            val url = BackendClient.files.downloadUrl(item.fileId, account).downloadUrl ?: throw IllegalStateException()
            val bytes = Uploads.download(url)
            image = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } ?: throw IllegalStateException()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val shape = RoundedCornerShape(16.dp)
    when {
        image != null -> Image(image!!, tr("Photo from their SOS"), Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(shape),
            contentScale = ContentScale.Fit)
        failed -> Text(tr("Couldn't load this photo."), color = p.accent, fontSize = 12.sp)
        else -> Box(Modifier.fillMaxWidth().height(120.dp).background(p.card, shape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = p.accent, modifier = Modifier.size(24.dp))
        }
    }
}
