package `in`.maitri.app.network

import `in`.maitri.app.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.http.*
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Private files in R2: the phone gets a presigned URL, PUTs the bytes straight to storage, then confirms. */
data class UploadRequestDto(val kind: String, @SerializedName("mime_type") val mimeType: String,
    @SerializedName("size_bytes") val sizeBytes: Long, @SerializedName("doc_type") val docType: String? = null,
    @SerializedName("incident_id") val incidentId: String? = null)
data class UploadTicketDto(@SerializedName("file_id") val fileId: String, @SerializedName("upload_url") val uploadUrl: String,
    val headers: Map<String, String>)
data class FileStatusDto(@SerializedName("file_id") val fileId: String?, val status: String? = null)
data class DownloadDto(@SerializedName("file_id") val fileId: String?, @SerializedName("download_url") val downloadUrl: String?,
    @SerializedName("mime_type") val mimeType: String? = null)
/** An SOS photo or voice note, visible to the sender and to circle members alerted about that SOS. */
data class SosMediaItemDto(@SerializedName("file_id") val fileId: String, val kind: String,
    @SerializedName("mime_type") val mimeType: String, @SerializedName("uploaded_at") val uploadedAt: String?)
data class SosMediaListDto(val items: List<SosMediaItemDto> = emptyList())
data class PhotoRequestDto(@SerializedName("file_id") val fileId: String)
data class VolunteerDocumentDto(@SerializedName("doc_type") val docType: String, @SerializedName("file_id") val fileId: String)

interface FilesApi {
    @POST("api/files/upload-url") suspend fun uploadUrl(@Body body: UploadRequestDto, @Tag expected: ExpectedAccount): UploadTicketDto
    @POST("api/files/{id}/complete") suspend fun complete(@Path("id") id: String, @Tag expected: ExpectedAccount): FileStatusDto
    @DELETE("api/files/{id}") suspend fun delete(@Path("id") id: String, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @GET("api/files/{id}/download-url") suspend fun downloadUrl(@Path("id") id: String, @Tag expected: ExpectedAccount): DownloadDto
    @POST("api/files/profile-photo") suspend fun setProfilePhoto(@Body body: PhotoRequestDto, @Tag expected: ExpectedAccount): FileStatusDto
    @GET("api/files/profile-photo") suspend fun profilePhoto(@Tag expected: ExpectedAccount): DownloadDto
    @GET("api/files/incidents/{id}/media") suspend fun incidentMedia(@Path("id") incidentId: String, @Tag expected: ExpectedAccount): SosMediaListDto
}

class UploadException(message: String) : IOException(message)

object Uploads {
    const val MAX_BYTES = 10L * 1024 * 1024
    private val DOCUMENT_TYPES = setOf("application/pdf", "image/jpeg", "image/png", "image/webp")

    /** Reads a picked or captured file; images over 10 MB are re-encoded as JPEG so they fit. */
    suspend fun read(context: Context, uri: Uri): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
        if (mime !in DOCUMENT_TYPES) throw UploadException("Use a PDF, JPG or PNG file.")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                // A PDF can't be shrunk here; stop reading early instead of filling memory.
                if (mime == "application/pdf" && total > MAX_BYTES) throw UploadException("This file is larger than 10 MB.")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw UploadException("Couldn't open that file.")
        if (bytes.size <= MAX_BYTES) bytes to mime else (shrinkImage(bytes, 2560) ?: throw UploadException("This file is larger than 10 MB.")) to "image/jpeg"
    }

    /** Square centre crop, scaled to [size] px, as JPEG: profile photos. */
    fun squareJpeg(bytes: ByteArray, size: Int = 1024): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, minOf(size, side), minOf(size, side), true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    /** Re-encodes an image that is over the limit; null if it still doesn't fit. */
    fun fitImage(bytes: ByteArray): ByteArray? = shrinkImage(bytes, 2560)

    private fun shrinkImage(bytes: ByteArray, maxSide: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            .takeIf { it.size <= MAX_BYTES }
    }

    /** Presign, PUT to R2 (no Maitri credentials on that request), confirm. Returns the file id. */
    suspend fun upload(bytes: ByteArray, mime: String, kind: String, account: ExpectedAccount,
                       docType: String? = null, incidentId: String? = null): String {
        val ticket = BackendClient.files.uploadUrl(UploadRequestDto(kind, mime, bytes.size.toLong(), docType, incidentId), account)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(ticket.uploadUrl)
                .put(bytes.toRequestBody(ticket.headers["Content-Type"]?.toMediaType()))
                .build()
            BackendClient.rawOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UploadException("Upload interrupted. Try again.")
            }
        }
        BackendClient.files.complete(ticket.fileId, account)
        return ticket.fileId
    }

    /** Downloads a file through its short-lived URL (profile photo). */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        BackendClient.rawOkHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed")
            response.body?.bytes() ?: throw IOException("Download failed")
        }
    }
}

/** Words for upload failures; never an HTTP code. */
fun uploadErrorMessage(error: Throwable): String {
    if (error is UploadException) return error.message ?: tr("Upload failed. Try again.")
    if (error is HttpException) {
        val code = runCatching {
            JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject["detail"].asJsonObject["code"].asString
        }.getOrNull()
        return when (code) {
            "STORAGE_NOT_CONFIGURED" -> tr("Document upload isn't switched on yet on the Maitri server. Try again later.")
            "FILE_TYPE_NOT_ALLOWED" -> tr("Use a PDF, JPG or PNG file.")
            "FILE_TOO_LARGE" -> tr("This file is larger than 10 MB.")
            "UPLOAD_NOT_FOUND", "UPLOAD_SIZE_MISMATCH" -> tr("The upload didn't finish. Try again.")
            else -> if (error.code() == 409) tr("Your application is already submitted, so documents can't change now.")
                else tr("Upload failed. Try again.")
        }
    }
    return tr("Couldn't reach Maitri. Check your connection and try again.")
}
