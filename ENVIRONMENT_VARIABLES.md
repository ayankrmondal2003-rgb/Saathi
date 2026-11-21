# Controlled deployment settings

| Setting | Source and handling |
|---|---|
| DATABASE_URL | Owner supplies the existing surakshanet_db Internal Database URL privately at the Blueprint prompt. Settings normalizes the PostgreSQL scheme for asyncpg. Alembic enables PostGIS through that same connection before applying migrations. |
| REDIS_URL | Private Key Value connectionString. Preserve the scheme and authentication material issued by the provider. Required for distributed operation. |
| FIREBASE_PROJECT_ID | surakshanet-c4ec1, matching the Android client. |
| GOOGLE_APPLICATION_CREDENTIALS | /etc/secrets/firebase-admin.json on the API service. Store the JSON contents in the Render secret file. |
| TEST_ACCOUNT_UIDS | Private JSON array of the three verified Firebase UIDs. Empty array denies protected API access and new invitation sends. Restart all workers when changing it. |
| ENVIRONMENT | development for synthetic demo positions. Test-only incident checks and the allowlist remain active. |
| PORT | Render supplies the web listening port. |
| ALLOWED_ORIGINS | Defaults to an empty list. Native Android needs no browser CORS exception. |
| LOG_LEVEL | INFO by default. Uvicorn access logging is disabled by the Blueprint to avoid WebSocket ticket URLs in application access logs. |
| WORKER_ID | Leave unset with multiple Uvicorn workers. Each hub generates its own ID. |
| RADIUS_METERS | Defaults to 1000 with a server-enforced upper bound of 1000. |
| MAX_LOCATION_AGE_SECONDS / MAX_ACCURACY_METERS | Defaults of 120 seconds / 100 metres for discovery. |
| CLOSED_LOCATION_HOURS / EXPIRED_INVITATION_DAYS / EXPIRED_GRANT_DAYS / AUDIT_DAYS | Maintenance defaults are 24 hours / 7 days / 7 days / 30 days. Cleanup requires running the maintenance job. |
| surakshaDevApiUrl | Android debug Gradle property or uncommitted root local.properties. Supply only the verified HTTPS origin. |

The server has no SOS_BROADCAST_MODE, SMS_MODE or FIREBASE_ADMIN_CREDENTIALS setting. Public responder discovery and emergency dispatch cannot be enabled by setting those names. TEST_DATABASE_URL and TEST_REDIS_URL are for the isolated test runner. Never point tests at hosted demo data.
