package `in`.maitri.app.network

import `in`.maitri.app.tr

import `in`.maitri.app.data.EmergencyContact
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import retrofit2.HttpException
import retrofit2.http.*
import java.io.IOException
import java.time.Instant

/** Server copy of a circle member (`/api/trusted-contacts`). SOS alert dispatch reads only this list. */
data class TrustedContactDto(
    @SerializedName("id") val id: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("normalized_phone_number") val normalizedPhoneNumber: String,
    @SerializedName("label") val label: String,
    @SerializedName("is_primary") val isPrimary: Boolean,
    @SerializedName("is_verified") val isVerified: Boolean,
    @SerializedName("source") val source: String,
    @SerializedName("notify_on_sos") val notifyOnSos: Boolean,
    @SerializedName("include_location_link") val includeLocationLink: Boolean,
    @SerializedName("notify_on_closure") val notifyOnClosure: Boolean,
    @SerializedName("allow_live_tracking") val allowLiveTracking: Boolean,
    @SerializedName("preferred_channel") val preferredChannel: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
) {
    fun toContact(owner: String) = EmergencyContact(
        id = id, ownerUserId = owner, displayName = displayName, phoneNumber = phoneNumber,
        normalizedPhoneNumber = normalizedPhoneNumber, label = label, isPrimary = isPrimary, isVerified = isVerified,
        source = source, notifyOnSos = notifyOnSos, includeLocationLink = includeLocationLink,
        notifyOnClosure = notifyOnClosure, allowLiveTracking = allowLiveTracking, preferredChannel = preferredChannel,
        createdAt = epoch(createdAt), updatedAt = epoch(updatedAt))

    private fun epoch(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}

/** Create and update share one body; on update, every field is sent so the server matches the app exactly. */
data class TrustedContactWriteDto(
    @SerializedName("display_name") val displayName: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("label") val label: String,
    @SerializedName("is_primary") val isPrimary: Boolean,
    @SerializedName("notify_on_sos") val notifyOnSos: Boolean,
    @SerializedName("include_location_link") val includeLocationLink: Boolean,
    @SerializedName("notify_on_closure") val notifyOnClosure: Boolean,
    @SerializedName("allow_live_tracking") val allowLiveTracking: Boolean,
    @SerializedName("preferred_channel") val preferredChannel: String
) {
    companion object {
        fun of(c: EmergencyContact) = TrustedContactWriteDto(c.displayName, c.phoneNumber, c.label, c.isPrimary,
            c.notifyOnSos, c.includeLocationLink, c.notifyOnClosure, c.allowLiveTracking,
            if (c.preferredChannel == "APP") "APP" else "SMS")
    }
}

interface TrustedContactApi {
    @GET("api/trusted-contacts") suspend fun list(@Tag expected: ExpectedAccount): List<TrustedContactDto>
    @POST("api/trusted-contacts") suspend fun create(@Body body: TrustedContactWriteDto, @Tag expected: ExpectedAccount): TrustedContactDto
    @PUT("api/trusted-contacts/{id}") suspend fun update(@Path("id") id: String, @Body body: TrustedContactWriteDto, @Tag expected: ExpectedAccount): TrustedContactDto
    @DELETE("api/trusted-contacts/{id}") suspend fun delete(@Path("id") id: String, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @POST("api/trusted-contacts/{id}/primary") suspend fun primary(@Path("id") id: String, @Tag expected: ExpectedAccount): TrustedContactDto
}

/** What has to change on the server to turn [before] into [after]. Pure, so the sync rules are unit-tested. */
internal data class ContactChanges(
    val added: List<EmergencyContact>,
    val removed: List<EmergencyContact>,
    val changed: List<EmergencyContact>,
    /** Local id of the contact that newly became primary, or null if the primary did not change. */
    val newPrimary: String?
)

internal fun contactChanges(before: List<EmergencyContact>, after: List<EmergencyContact>): ContactChanges {
    val old = before.associateBy { it.id }
    val now = after.associateBy { it.id }
    fun fields(c: EmergencyContact) = listOf(c.displayName, c.phoneNumber, c.label, c.notifyOnSos, c.includeLocationLink,
        c.notifyOnClosure, c.allowLiveTracking, c.preferredChannel)
    val primaryBefore = before.firstOrNull { it.isPrimary }?.id
    val primaryAfter = after.firstOrNull { it.isPrimary }?.id
    return ContactChanges(
        added = after.filter { it.id !in old },
        removed = before.filter { it.id !in now },
        changed = after.filter { c -> old[c.id]?.let { fields(it) != fields(c) } == true },
        newPrimary = primaryAfter.takeIf { it != null && it != primaryBefore })
}

/**
 * Applies a local edit to the server, then returns the server's list (the new source of truth).
 * Deletions run first so a replaced phone number is free before it is re-added.
 */
internal suspend fun pushContactChanges(api: TrustedContactApi, account: ExpectedAccount,
                                        changes: ContactChanges): List<TrustedContactDto> {
    val serverIds = mutableMapOf<String, String>()
    for (c in changes.removed) {
        val response = api.delete(c.id, account)
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }
    for (c in changes.changed) serverIds[c.id] = api.update(c.id, TrustedContactWriteDto.of(c), account).id
    for (c in changes.added) serverIds[c.id] = api.create(TrustedContactWriteDto.of(c), account).id
    changes.newPrimary?.let { local -> api.primary(serverIds[local] ?: local, account) }
    return api.list(account)
}

/** First sync for contacts saved before the circle was server-backed: upload them once. Duplicates (409) are skipped. */
internal suspend fun uploadLocalContacts(api: TrustedContactApi, account: ExpectedAccount,
                                         local: List<EmergencyContact>): List<TrustedContactDto> {
    for (c in local.sortedByDescending { it.isPrimary }) {
        try { api.create(TrustedContactWriteDto.of(c), account) }
        catch (e: HttpException) { if (e.code() != 409) throw e }
    }
    return api.list(account)
}

/** Plain message for a failed circle sync. Never implies the contact will be alerted. */
internal fun contactSyncFailure(error: Exception): String {
    if (error is IOException) return tr("Couldn't reach the server. Circle changes weren't saved, so they won't be used for SOS alerts.")
    val detail = (error as? HttpException)?.let { e ->
        runCatching { JsonParser.parseString(e.response()?.errorBody()?.string()).asJsonObject["detail"].asString }.getOrNull()
    }
    return when {
        detail != null && (error as HttpException).code() in setOf(409, 422) -> detail
        error is HttpException && error.code() in setOf(401, 403) -> tr("Sign in again to save your circle.")
        else -> tr("Circle changes couldn't be saved to the server, so they won't be used for SOS alerts.")
    }
}
