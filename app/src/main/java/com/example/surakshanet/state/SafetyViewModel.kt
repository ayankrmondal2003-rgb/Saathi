package com.example.surakshanet.state

import android.app.Application
import android.os.SystemClock
import com.example.surakshanet.location.LocalTrackingRepository
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import com.google.firebase.auth.FirebaseAuth
import com.example.surakshanet.BuildConfig
import com.example.surakshanet.network.*
import com.example.surakshanet.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SafetyViewModel(app: Application) : AndroidViewModel(app) {
    private val tracking = LocalTrackingRepository.get(app)
    private val profiles: UserProfileRepository = LocalUserProfileRepository(app.getSharedPreferences("local_profiles", 0))
    private val repository: ContactRepository = LocalContactRepository(app.getSharedPreferences("trusted_contacts", 0))
    private val contactSyncPrefs = app.getSharedPreferences("trusted_contacts_sync", 0)
    private val contactMutex = Mutex()

    /** Server circle API, only for the signed-in account; demo and offline-only builds stay local. */
    private fun contactRemote(uid: String): TrustedContactApi? {
        if (BuildConfig.API_BASE_URL.isBlank()) return null
        val signedIn = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        return if (signedIn == uid) BackendClient.contacts else null
    }

    private fun applyServerContacts(uid: String, server: List<TrustedContactDto>, message: String? = null) {
        val list = server.map { it.toContact(uid) }
        repository.save(uid, list)
        if (current.user?.uid == uid) mutable.value = current.copy(contacts = list, contactsMessage = message ?: current.contactsMessage)
    }

    /** Loads the server circle; on first sync uploads contacts that were only saved on this phone. */
    fun refreshContacts() {
        val uid = current.user?.uid ?: return
        val api = contactRemote(uid) ?: return
        viewModelScope.launch {
            contactMutex.withLock {
                val account = ExpectedAccount(uid)
                try {
                    var server = api.list(account)
                    val local = current.contacts
                    if (server.isEmpty() && local.isNotEmpty() && !contactSyncPrefs.getBoolean("migrated.$uid", false))
                        server = uploadLocalContacts(api, account, local)
                    contactSyncPrefs.edit().putBoolean("migrated.$uid", true).apply()
                    applyServerContacts(uid, server)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (current.user?.uid == uid) mutable.value = current.copy(contactsMessage =
                        "Couldn't load your circle from the server. Contacts shown here may not be used for SOS alerts.")
                }
            }
        }
    }

    fun clearContactsMessage() { mutable.value = current.copy(contactsMessage = null) }
    private val mutable = MutableStateFlow(DemoState())
    val state = mutable.asStateFlow()
    private val current get() = mutable.value

    init {
        viewModelScope.launch {
            tracking.session.collect { session ->
                if (session.id.isEmpty() && tracking.session.value.id.isEmpty() && current.incident?.status == IncidentStatus.ACTIVE)
                    mutable.value = current.copy(incident = current.incident?.copy(status = IncidentStatus.ENDED))
            }
        }
    }

    fun bind(identity: Identity?) {
        val stored = tracking.session.value
        if (stored.owner.isNotEmpty() && (stored.owner != identity?.uid || !identity.verified)) tracking.close()
        if (identity == null || !identity.verified) {
            tracking.sync.wake(null)
            mutable.value = DemoState()
            return
        }
        tracking.restoreConfirmed(identity.uid)
        tracking.sync.wake(identity.uid)
        val profile = profiles.load(identity)
        mutable.value = if (current.user?.uid == identity.uid) current.copy(user = profile)
            else {
                val restored = tracking.session.value.takeIf { it.owner == identity.uid && it.id.isNotEmpty() }?.let {
                    val elapsed = (System.currentTimeMillis() - it.startedAt).coerceAtLeast(0)
                    SosIncident(SystemClock.elapsedRealtime() - elapsed)
                }
                DemoState(profile, repository.load(identity.uid), incident = restored, responder = profile.responder)
            }
        refreshContacts()
    }

    fun start(share: Boolean) {
        val user = current.user ?: return
        if (current.incident?.status != IncidentStatus.ACTIVE) {
            tracking.begin(user.uid)
            if (share) tracking.sync.enable(user.uid, tracking.session.value.id)
            val session = tracking.session.value
            val lat = session.latest?.latitude
            val lng = session.latest?.longitude
            val statuses = com.example.surakshanet.notification.SosContactNotifierHelper.buildContactAlertStatuses(
                contacts = current.contacts,
                latitude = lat,
                longitude = lng,
                recordedAtMillis = session.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                isSimulated = true
            )
            mutable.value = current.copy(
                incident = SosIncident(SystemClock.elapsedRealtime()),
                contactAlertStatuses = statuses,
                lastAlertBatchDispatchedAt = System.currentTimeMillis()
            )
        }
    }
    fun end() {
        tracking.close()
        val closureStatuses = current.contactAlertStatuses.map { alert ->
            val contact = current.contacts.find { it.id == alert.contactId }
            if (contact?.notifyOnClosure == true) {
                alert.copy(
                    deliveryStatus = ContactDeliveryStatus.PROVIDER_ACCEPTED,
                    messagePreview = "SurakshaNet SOS: Incident has been resolved. I am safe.",
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                alert
            }
        }
        mutable.value = current.copy(
            incident = current.incident?.copy(status = IncidentStatus.ENDED),
            contactAlertStatuses = closureStatuses
        )
    }

    fun dispatchSosContactAlerts(lat: Double? = null, lng: Double? = null, isSimulated: Boolean = true) {
        val session = tracking.session.value
        val effectiveLat = lat ?: session.latest?.latitude
        val effectiveLng = lng ?: session.latest?.longitude
        val statuses = com.example.surakshanet.notification.SosContactNotifierHelper.buildContactAlertStatuses(
            contacts = current.contacts,
            latitude = effectiveLat,
            longitude = effectiveLng,
            recordedAtMillis = System.currentTimeMillis(),
            isSimulated = isSimulated
        )
        mutable.value = current.copy(
            contactAlertStatuses = statuses,
            lastAlertBatchDispatchedAt = System.currentTimeMillis()
        )
    }

    fun testContactAlert(contactId: String) {
        val session = tracking.session.value
        val lat = session.latest?.latitude
        val lng = session.latest?.longitude
        val contact = current.contacts.find { it.id == contactId } ?: return
        val testStatus = ContactAlertStatus(
            contactId = contact.id,
            displayName = contact.displayName,
            maskedPhone = EmergencyContact.maskPhone(contact.phoneNumber),
            isPrimary = contact.isPrimary,
            deliveryStatus = ContactDeliveryStatus.PROVIDER_ACCEPTED,
            messagePreview = com.example.surakshanet.notification.SosContactNotifierHelper.formatEmergencyMessage(
                contactName = contact.displayName,
                latitude = lat,
                longitude = lng,
                recordedAtMillis = System.currentTimeMillis(),
                includeLocation = contact.includeLocationLink
            ),
            locationDisclosed = contact.includeLocationLink && lat != null && lng != null,
            trackingGranted = contact.allowLiveTracking,
            updatedAt = System.currentTimeMillis()
        )
        val updated = current.contactAlertStatuses.filterNot { it.contactId == contactId } + testStatus
        mutable.value = current.copy(contactAlertStatuses = updated)
    }

    fun enable(enabled: Boolean) = updatePreferences(Responder(enabled))
    fun available(value: Boolean) = updatePreferences(current.responder.copy(available = current.responder.enabled && value))
    fun updateUserProfile(user: UserProfile) {
        mutable.value = current.copy(user = user)
    }
    private fun updatePreferences(responder: Responder) {
        val user = current.user ?: return
        profiles.savePreferences(user.uid, responder)
        mutable.value = current.copy(user = user.copy(responder = responder), responder = responder)
    }
    fun respond(response: Response) {
        if (current.responder.available) mutable.value = current.copy(responder = current.responder.copy(response = response))
    }
    private fun save(items: List<EmergencyContact>) {
        val uid = current.user?.uid ?: return
        val before = current.contacts
        repository.save(uid, items)
        mutable.value = current.copy(contacts = items)
        val api = contactRemote(uid) ?: return
        viewModelScope.launch {
            contactMutex.withLock {
                val account = ExpectedAccount(uid)
                try {
                    applyServerContacts(uid, pushContactChanges(api, account, contactChanges(before, items)))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Show what the server actually holds; if it is unreachable, undo the unsaved edit.
                    val message = contactSyncFailure(e)
                    try { applyServerContacts(uid, api.list(account), message) }
                    catch (e2: CancellationException) { throw e2 }
                    catch (_: Exception) {
                        repository.save(uid, before)
                        if (current.user?.uid == uid) mutable.value = current.copy(contacts = before, contactsMessage = message)
                    }
                }
            }
        }
    }

    fun remove(id: String) {
        val remaining = current.contacts.filterNot { it.id == id }
        val finalList = if (remaining.isNotEmpty() && remaining.none { it.isPrimary }) {
            remaining.mapIndexed { index, contact ->
                if (index == 0) contact.copy(isPrimary = true) else contact
            }
        } else {
            remaining
        }
        save(finalList)
    }

    fun setPrimary(id: String) {
        if (current.contacts.isEmpty()) return
        val updated = current.contacts.map {
            it.copy(isPrimary = (it.id == id))
        }
        save(updated)
    }

    fun toggleSosAlert(id: String, enabled: Boolean) {
        val updated = current.contacts.map {
            if (it.id == id) it.copy(notifyOnSos = enabled, updatedAt = System.currentTimeMillis()) else it
        }
        save(updated)
    }

    fun addOrUpdateContact(
        id: String?,
        name: String,
        phone: String,
        label: String = "Emergency Contact",
        isPrimary: Boolean = false,
        source: String = EmergencyContact.SOURCE_MANUAL,
        notifyOnSos: Boolean = true,
        includeLocationLink: Boolean = true,
        notifyOnClosure: Boolean = true,
        allowLiveTracking: Boolean = true,
        preferredChannel: String = EmergencyContact.CHANNEL_SMS
    ): String? {
        val nameErr = EmergencyContact.validateName(name)
        if (nameErr != null) return nameErr
        val phoneErr = EmergencyContact.validatePhone(phone)
        if (phoneErr != null) return phoneErr

        val normalized = EmergencyContact.normalizePhone(phone)
        val duplicate = current.contacts.find { it.normalizedPhoneNumber == normalized && it.id != id }
        if (duplicate != null) {
            return "This phone number is already saved as \"${duplicate.displayName}\"."
        }

        val now = System.currentTimeMillis()
        val uid = current.user?.uid.orEmpty()
        val shouldBePrimary = isPrimary || current.contacts.isEmpty() || (id != null && current.contacts.find { it.id == id }?.isPrimary == true)

        val newContact = EmergencyContact(
            id = id ?: UUID.randomUUID().toString(),
            ownerUserId = uid,
            displayName = name.trim(),
            phoneNumber = phone.trim(),
            normalizedPhoneNumber = normalized,
            label = label.trim().ifBlank { "Emergency Contact" },
            isPrimary = shouldBePrimary,
            isVerified = false,
            source = source,
            notifyOnSos = notifyOnSos,
            includeLocationLink = includeLocationLink,
            notifyOnClosure = notifyOnClosure,
            allowLiveTracking = allowLiveTracking,
            preferredChannel = preferredChannel,
            createdAt = current.contacts.find { it.id == id }?.createdAt ?: now,
            updatedAt = now
        )

        val updatedList = if (id == null) {
            val base = if (shouldBePrimary) current.contacts.map { it.copy(isPrimary = false) } else current.contacts
            base + newContact
        } else {
            current.contacts.map { existing ->
                when {
                    existing.id == id -> newContact
                    shouldBePrimary -> existing.copy(isPrimary = false)
                    else -> existing
                }
            }
        }

        val finalList = if (updatedList.isNotEmpty() && updatedList.none { it.isPrimary }) {
            updatedList.mapIndexed { i, c -> if (i == 0) c.copy(isPrimary = true) else c }
        } else {
            updatedList
        }

        save(finalList)
        return null
    }

    fun contact(id: String?, name: String, phone: String): String? {
        return addOrUpdateContact(id = id, name = name, phone = phone)
    }
}
