package `in`.maitri.app.data

import `in`.maitri.app.tr

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import `in`.maitri.app.R

/** The user closed the Google account sheet; not an error worth a message. */
class GoogleSignInCancelled : Exception("Google sign-in cancelled")

/** Google Sign-In could not produce an ID token; [userMessage] is safe to show. */
class GoogleSignInUnavailable(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/**
 * Shows Google's account picker through Credential Manager and returns a Google ID token for Firebase.
 * The audience is the project's web OAuth client (client_type 3 in google-services.json), which the
 * google-services plugin exposes as R.string.default_web_client_id.
 */
suspend fun requestGoogleIdToken(activity: Activity): String {
    val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val response = try {
        CredentialManager.create(activity).getCredential(activity, request)
    } catch (e: GetCredentialCancellationException) {
        throw GoogleSignInCancelled()
    } catch (e: NoCredentialException) {
        throw GoogleSignInUnavailable(tr("Add a Google account to this phone, or use phone or email sign-in."), e)
    } catch (e: GetCredentialException) {
        throw GoogleSignInUnavailable(tr("Google sign-in is unavailable right now. Use phone or email sign-in."), e)
    }
    val credential = response.credential
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw GoogleSignInUnavailable(tr("Google returned an unexpected credential. Use phone or email sign-in."))
}
