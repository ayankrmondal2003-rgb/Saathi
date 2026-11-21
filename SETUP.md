# Controlled Render demo setup

## 1. Prepare three verified Firebase identities

1. Open Firebase Console and select project `surakshanet-c4ec1`. Under Project settings → General → Your apps, confirm the Android package is `com.example.surakshanet`. The checked-in client configuration must correspond to this app.
2. Open Build → Authentication → Sign-in method. Enable Email/Password and save. Under Project settings → Cloud Messaging verify the Firebase Cloud Messaging API (V1) is enabled. If disabled, open the linked Google Cloud API page for this project and enable it.
3. On each demo installation select Create Account. Use a separate email inbox you control and a unique password for each role (A victim, B and C responders). Store passwords in your password manager. Open the Firebase verification link from each inbox and refresh verification on that installation. Account creation and email verification can precede Render provisioning.
4. In Authentication → Users locate each exact email and copy its User UID. Privately assemble a JSON array of the three UID strings for Render's TEST_ACCOUNT_UIDS secret. Use an empty array to deny all accounts during initial setup. If registration reports an existing account, sign in or reset its password. Retry profile synchronization after allowlisting.

## 2. Prepare the backend credential privately

In Google Cloud IAM for the same project, create a dedicated demo service account. Grant Firebase Authentication Viewer for user lookup during token revocation checks and Firebase Cloud Messaging API Admin for message sends. Under IAM & Admin → Service Accounts → the demo account → Keys → Add key → Create new key → JSON, save the download outside the repository. Confirm its project_id matches the app. The backend reads the file only at runtime. Rotate or delete the key after the exercise. Keep it out of chat, Gradle, Docker build context and version control.

## 3. Create the Render resources

Follow [free-tier deployment](RENDER_DEPLOYMENT.md) to verify the existing database plan and region, configure the secret file, and check readiness before building the Android app.

## 4. Install and rehearse on the Samsung

Add `surakshaDevApiUrl=` followed by the real HTTPS origin and a trailing slash to the root local.properties. A Gradle project property takes precedence, so remove any stale override from user Gradle properties. Sync, then run the following from the project root.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s RZ8R903LKWR install -r app/build/outputs/apk/debug/app-debug.apk
```

On the Samsung open the actual HTTPS health URL, then sign in and confirm the profile sync. In Profile check backend health, register the device and send the self-test. Record acceptance, receipt, display and opening separately. Inspect Settings → Apps → SurakshaNet → Notifications and the urgent channel. Use demo accounts and synthetic positions for the three-phone guide. To end the demo, close active incidents, remove test UIDs and disable or suspend the deployment.

## Local isolated regression

From backend run `.\.venv\Scripts\python.exe tests/run_isolated.py -q --tb=long`. The runner creates and removes a private PostGIS cluster. For a locally provisioned development environment the supported entry point is `.\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000`.

Provider references: [Render Blueprint specification](https://render.com/docs/blueprint-spec), [Render secret files](https://render.com/docs/configure-environment-variables), [Render PostgreSQL extensions](https://render.com/docs/postgresql-extensions), [Firebase email/password on Android](https://firebase.google.com/docs/auth/android/password-auth).
