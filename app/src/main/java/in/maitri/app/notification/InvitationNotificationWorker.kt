package `in`.maitri.app.notification

import `in`.maitri.app.tr

import `in`.maitri.app.data.isMaitriVerified
import android.content.Context
import androidx.work.*
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

class InvitationNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val invitation = inputData.getString("invitation") ?: return Result.failure()
        val incident = inputData.getString("incident") ?: return Result.failure()
        val expires = inputData.getLong("expires", 0)
        fun eligible() = expires > System.currentTimeMillis() && FirebaseAuth.getInstance().currentUser?.let {
            it.uid == uid && it.isMaitriVerified()
        } == true
        if (!eligible()) return Result.success()
        try {
            val item = BackendClient.api.invitations(ExpectedAccount(uid)).firstOrNull {
                it.id == invitation && it.incidentId == incident && it.status == "PENDING"
            }
            // Only a distance band before accepting, never coordinates. "Practice" only for real test SOS.
            val title = if (item?.isTest == true) tr("Practice SOS nearby") else tr("Someone needs help")
            val body = item?.distanceBand?.let { tr("{0} away · Tap to respond if you can do so safely.", tr(it)) }
                ?: tr("Nearby · Tap to respond if you can do so safely.")
            if (item != null && eligible() && NotificationHelper.showSosNotification(applicationContext, invitation,
                    incident, title, body, "RESPONDER_INVITATION_CREATED", timeoutMillis = expires - System.currentTimeMillis())) {
                SosFirebaseMessagingService.saveLastAlertTimestamp(applicationContext, System.currentTimeMillis())
            }
            return Result.success()
        } catch (e: HttpException) {
            if (e.code() < 500 && e.code() !in listOf(408, 429)) return Result.failure()
        } catch (_: IOException) {
            // The invitation is checked against its original deadline on every attempt.
        }
        return if (runAttemptCount < 4 && eligible()) Result.retry() else Result.failure()
    }

    companion object {
        fun enqueue(context: Context, uid: String, invitation: String, incident: String, expiry: Long) {
            val request = OneTimeWorkRequestBuilder<InvitationNotificationWorker>()
                .setInputData(workDataOf("uid" to uid, "invitation" to invitation, "incident" to incident, "expires" to expiry))
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("invitation-$invitation", ExistingWorkPolicy.KEEP, request)
        }
    }
}
