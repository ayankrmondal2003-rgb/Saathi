package `in`.maitri.app.network

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SOS voice notes and photos (screen 08). Captured into app-private storage, then uploaded by WorkManager to
 * R2 as SOS_VOICE / SOS_PHOTO for this incident. Offline or on a cold server they wait and retry; the local
 * file is deleted once the server has it. Only the owner can download them, and the server deletes them after
 * 30 days.
 */
object SosMedia {
    const val MAX_VOICE_MS = 60_000
    const val TAG = "sos-media"

    /** Lets the SOS screen show photo and voice uploads under their own tiles. */
    fun kindTag(kind: String) = "$TAG:kind:$kind"
    fun dir(context: Context) = File(context.filesDir, "sos-media").apply { mkdirs() }

    fun newFile(context: Context, prefix: String, extension: String) =
        File(dir(context), "$prefix-${System.currentTimeMillis()}.$extension")

    fun enqueue(context: Context, file: File, kind: String, mime: String, incidentId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val request = OneTimeWorkRequestBuilder<SosMediaWorker>()
            .setInputData(workDataOf("path" to file.absolutePath, "kind" to kind, "mime" to mime,
                "incident" to incidentId, "uid" to uid))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG).addTag("$TAG:$incidentId").addTag(kindTag(kind))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sos-media:${file.name}", ExistingWorkPolicy.KEEP, request)
    }

    @Suppress("DEPRECATION")
    fun recorder(context: Context, file: File): MediaRecorder =
        (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setMaxDuration(MAX_VOICE_MS)
            setOutputFile(file.absolutePath)
            prepare()
        }
}

class SosMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val file = File(inputData.getString("path") ?: return Result.failure())
        if (!file.exists()) return Result.success()
        val uid = inputData.getString("uid") ?: return Result.failure()
        // Another account signed in since: never upload one person's evidence under another's account.
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return if (runAttemptCount < 20) Result.retry() else Result.failure()
        var bytes = file.readBytes()
        val mime = inputData.getString("mime") ?: return Result.failure()
        if (bytes.size > Uploads.MAX_BYTES && mime.startsWith("image/")) bytes = Uploads.fitImage(bytes) ?: return Result.failure()
        return try {
            Uploads.upload(bytes, mime, inputData.getString("kind") ?: return Result.failure(), ExpectedAccount(uid),
                incidentId = inputData.getString("incident"))
            file.delete()
            Result.success()
        } catch (e: retrofit2.HttpException) {
            // 4xx other than conflicts can't succeed later (closed incident, wrong type); keep trying otherwise.
            if (e.code() in 400..499 && e.code() !in setOf(408, 409, 429)) { file.delete(); Result.failure() }
            else if (runAttemptCount < 15) Result.retry() else Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 15) Result.retry() else Result.failure()
        }
    }
}
