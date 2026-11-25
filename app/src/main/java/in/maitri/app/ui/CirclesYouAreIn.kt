package `in`.maitri.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.network.AddBackRequest
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.MembershipDto
import `in`.maitri.app.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * "Circles you're in": everyone whose SOS alerts reach this account. Links are one-way, so each card offers to add that
 * person back; they get the usual circle request and the link is mutual only once they accept.
 */
@Composable
internal fun CirclesYouAreIn(memberships: List<MembershipDto>, uid: String?, p: MaitriPalette, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var askPhone by remember { mutableStateOf<MembershipDto?>(null) }

    fun addBack(m: MembershipDto, phone: String?) {
        val account = uid?.let { ExpectedAccount(it) } ?: return
        busy = m.linkId
        scope.launch {
            try {
                BackendClient.circle.addBack(m.linkId, AddBackRequest(phone?.trim()?.ifBlank { null }), account)
                askPhone = null
                Toast.makeText(context, tr("Request sent to {0}", m.ownerName), Toast.LENGTH_SHORT).show()
                onChanged()
            } catch (e: CancellationException) { throw e }
            catch (e: HttpException) {
                // 422 without a number: their phone isn't verified, so ask for it. With a number: the number is invalid.
                if (e.code() == 422 && phone == null) askPhone = m
                else Toast.makeText(context, if (e.code() == 422) tr("Check the number and try again.")
                    else tr("Couldn't send the request. Try again."), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(context, tr("Couldn't send the request. Try again."), Toast.LENGTH_LONG).show()
            } finally { busy = null }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Circles you're in"), Modifier.semantics { heading() }, color = p.primary, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold)
        memberships.forEachIndexed { index, m ->
            Box(Modifier.fadeInOnFirstLoad(index)) { MembershipCard(m, p, busy == m.linkId) { addBack(m, null) } }
        }
    }

    askPhone?.let { m ->
        var phone by remember(m.linkId) { mutableStateOf("") }
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { askPhone = null }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(tr("Add {0}", m.ownerName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Enter {0}'s phone number. They'll get a request and can accept it.", m.ownerName))
                    OutlinedTextField(phone, { phone = it.take(20) }, singleLine = true, label = { Text(tr("Phone number")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            },
            confirmButton = {
                TextButton(enabled = phone.count { it.isDigit() } >= 7 && busy == null, onClick = { addBack(m, phone) }) {
                    Text(tr("Send request"), color = p.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { askPhone = null }) { Text(tr("Cancel"), color = p.accent) } })
    }
}

@Composable
private fun MembershipCard(m: MembershipDto, p: MaitriPalette, busy: Boolean, onAddBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(p.surface, CircleShape), contentAlignment = Alignment.Center) {
                Text(m.ownerName.trim().take(1).uppercase().ifBlank { "?" }, color = p.accent, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.ownerName, color = p.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("You get their SOS alerts"), color = p.secondary, fontSize = 12.sp)
                approvalDate(m.since)?.let { Text(tr("Since {0}", it), color = p.secondary, fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (m.addedBack) {
            "LINKED" -> Row(verticalAlignment = Alignment.CenterVertically) {
                VerifiedCheck(p, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("In each other's circles"), color = p.verified, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            "REQUESTED" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp), tint = p.pending)
                Spacer(Modifier.width(8.dp))
                Text(tr("Request sent. {0} needs to accept.", m.ownerName), color = p.secondary, fontSize = 13.sp)
            }
            else -> OutlinedButton(onClick = onAddBack, enabled = !busy, border = BorderStroke(1.dp, p.accent)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                else Text(tr("Add {0} to my circle too", m.ownerName), color = p.accent)
            }
        }
    }
}
