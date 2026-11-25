# Deploying Maitri at zero cost

| Piece | Service (free tier) | Notes |
|---|---|---|
| API | **Render Free** web service (`render.yaml`) | Sleeps after 15 idle minutes; a cron-job.org ping keeps it warm |
| Database | **Neon Free** Postgres + PostGIS | Scales to zero after 5 idle minutes; the API only wakes it when the app is in use |
| Files | **Cloudflare R2**, one private bucket | Phones upload and download directly with 5-minute presigned URLs |
| Realtime bus | In-process (no Redis) | One worker; Redis is optional and only needed for more than one |
| Keep-alive | **cron-job.org** | `GET /ready` every 10 minutes; never touches the database |

> **Not for a public launch.** A free stack can cold-start, sleep, or hit a monthly limit in the middle of an SOS. It is fine for testing with known accounts. Before real users rely on Maitri, move the API and database to always-on paid plans (see the release plan, Phase E). The app always shows **Call 112** during an SOS for this reason.

## Free-tier limits

Checked November 2026. Recheck the linked pages before relying on them.

| Service | Limit | What it means for Maitri |
|---|---|---|
| Render Free web service | Spins down after **15 minutes** with no inbound traffic; waking takes up to **a minute**. **750 instance hours** per workspace per month. 512 MB RAM, ephemeral disk. ([docs](https://render.com/docs/free)) | One always-on service uses about 744 hours a month, so keep **only one** free service running in the workspace. Never store files on the instance disk; the app doesn't. |
| Render Free Postgres | **Expires 30 days after creation**, then a 14-day grace period, then it is **deleted with all data**. ([docs](https://render.com/docs/free)) | This is why the database moves to Neon. Migrate before the expiry date shown in the Render dashboard. |
| Neon Free | **100 CU-hours** of compute per project per month; compute **suspends after 5 idle minutes** (can't be disabled); 1 GB storage per project (some older Neon pages say 0.5 GB; check the console); 5 GB egress. ([limits](https://neon.com/docs/introduction/limits), [pricing](https://neon.com/pricing)) | A 0.25 CU compute that never sleeps would use about 180 CU-hours, which is over the limit. Maitri therefore lets it sleep (see "How the database gets to sleep"). |
| Cloudflare R2 | **10 GB-month** storage, **1 M Class A** (writes) and **10 M Class B** (reads) operations per month; **egress is free**. Standard storage only. ([pricing](https://developers.cloudflare.com/r2/pricing/)) | Each upload is 1 Class A operation, and each confirm/download is 1 Class B operation. Files are capped at 10 MB. |
| cron-job.org | Free; runs jobs as often as every minute. | One job every 10 minutes is enough to keep Render awake. |

### How the database gets to sleep

- **`/ready` never opens a database connection.** Render's health check and the keep-alive ping only reach the API process. Use `/health/db` for a manual database check; it wakes Neon, so never point a monitor at it.
- **Background jobs only poll while the app is in use.** The geofence/expiry loop (every 30 s) and the notification outbox (every 2 s) touch the database only within `DB_IDLE_SECONDS` (default 900 = 15 min) of the last `/api/` request. When idle, each loop does one sweep per `IDLE_SWEEP_SECONDS` (default 3600) to expire orphaned incidents and stale shares.
- **An SOS keeps itself awake.** The phone uploads a fix every few seconds and screen 08 polls the incident, so time-critical work never waits for the hourly sweep.
- **Rough budget at 0.25 CU:**
  - The hourly sweeps wake the compute 24 times a day for about 5 minutes each: about **15 CU-hours a month**.
  - Each period of use keeps it awake about 20 minutes: about **0.08 CU-hours**.
  - Light testing stays well under 100. Watch **Neon console → Usage**.
- **If the limit is hit, Neon suspends the compute until next month.** Every API call then fails. The app keeps retrying the SOS and keeps **Call 112** on screen.

## 1. Neon: create the database

1. Sign up at [neon.com](https://neon.com) and create a project:
   - **Region:** AWS Asia Pacific (Singapore), `aws-ap-southeast-1`, the same region as the Render service.
   - **Postgres version:** 17.
2. In **Dashboard → Connect**:
   - Pick the default branch and database `neondb`.
   - Turn **Connection pooling off**, so you get the *direct* host without `-pooler`. Maitri holds a small pool itself. The pooled URL also works; the app then turns off asyncpg's statement cache automatically.
   - Copy the URL. It looks like `postgresql://neondb_owner:…@ep-…-….ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require`.
   - Keep it secret. The API accepts this form as-is: it switches to `postgresql+asyncpg`, maps `sslmode` to `ssl`, and drops `channel_binding`.
3. You don't need to install PostGIS yourself. On first start, `render-migrate.sh` runs `CREATE EXTENSION IF NOT EXISTS postgis` and the Alembic migrations (through **0012**). If you're importing data (step 2), the import creates PostGIS first.

## 2. Move the data from Render Postgres to Neon (before it expires)

Do this once, while the Render database still exists. Check **Render → surakshanet_db → Info** for the expiry date. You need PostgreSQL **17** client tools, because `pg_dump` must be at least the server's major version. Either install them, or use Docker as shown.

1. **Stop writes.** In Render, **surakshanet-api → Settings → Suspend Web Service**. In-flight SOS sessions can't sync during the copy.
2. **Get both URLs.** Render: **surakshanet_db → Connect → External Database URL**. Neon: the URL from step 1.
3. **Export** in custom format, without owners, privileges or comments. The roles and the PostGIS comment don't exist on Neon.
   ```powershell
   $env:RENDER_URL = "<External Database URL>"
   docker run --rm -v "${PWD}:/dump" postgres:17 pg_dump --format=custom --no-owner --no-acl --no-comments `
     --file=/dump/maitri.dump "$env:RENDER_URL"
   ```
   Without Docker: `pg_dump --format=custom --no-owner --no-acl --no-comments --file=maitri.dump "$env:RENDER_URL"`.
4. **Import into the empty Neon database.** The dump creates the PostGIS extension, the tables, `alembic_version` and the data.
   ```powershell
   $env:NEON_URL = "<Neon URL>"
   docker run --rm -v "${PWD}:/dump" postgres:17 pg_restore --no-owner --no-acl --exit-on-error `
     --dbname="$env:NEON_URL" /dump/maitri.dump
   ```
   If it stops on `relation … already exists`, the Neon database isn't empty. This happens if the API was started against it, which runs migrations. Create a fresh database in Neon, import there, and only then point `DATABASE_URL` at it.
5. **Check the copy.** Run both queries on the Render URL and on the Neon URL, and compare the results:
   ```powershell
   docker run --rm postgres:17 psql "$env:NEON_URL" -c "SELECT version_num FROM alembic_version" `
     -c "SELECT (SELECT count(*) FROM users) users, (SELECT count(*) FROM incidents) incidents, (SELECT count(*) FROM trusted_contacts) contacts, (SELECT count(*) FROM volunteer_applications) volunteers"
   ```
6. **Switch the API.** In **surakshanet-api → Environment**, replace `DATABASE_URL` with the Neon URL, then **Resume** the service and **Manual Deploy → Deploy latest commit**. On start it applies migration 0012 (`stored_files`).
7. **Verify.**
   - Open `https://surakshanet-api.onrender.com/ready` (expect `{"status":"ready",…}`) and `/health/db` once (expect `{"status":"ok"}`).
   - Sign in on a phone and check that your circle and profile are there.
8. **Retire the Render database** only after a day of normal use. Keep `maitri.dump` somewhere private (it contains personal data), then delete **surakshanet_db** in Render. Delete the local dump file once you no longer need it.

**Rollback:** until you delete it, the Render database is untouched. Put its Internal Database URL back into `DATABASE_URL` and redeploy. Anything written to Neon in the meantime is not copied back.

## 3. Cloudflare R2: private files

1. In the Cloudflare dashboard, **R2 Object Storage → Create bucket**:
   - **Name:** e.g. `maitri-private`.
   - **Location:** Automatic (or Asia-Pacific).
   - **Storage class:** Standard (the free tier covers only Standard).
2. Keep it **private**:
   - **Settings → Public access:** leave the *r2.dev subdomain* disabled and add **no** custom domain.
   - No CORS rule is needed: the Android app is not a browser.
3. **R2 → Manage API tokens → Create API token**:
   - **Permissions:** *Object Read & Write*.
   - **Specify bucket:** only `maitri-private`.
   - **TTL:** your choice.
   - Copy the **Access Key ID** and **Secret Access Key**; Cloudflare shows the secret once. The **Account ID** is on the R2 overview page.
4. In **surakshanet-api → Environment**, set `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`, then redeploy. `GET /api/volunteer/status` now reports `"secure_storage_ready": true`.
5. Optional, recommended: **bucket Settings → Object lifecycle rules**. Delete objects with prefix `sos_photo/` and `sos_voice/` after the retention period you publish in the privacy policy.

How it works (`backend/app/files.py`, `backend/app/storage.py`):
1. `POST /api/files/upload-url` with `{kind, mime_type, size_bytes[, doc_type][, incident_id]}` returns a presigned **PUT** (5 minutes). Type and size are part of the signature.
2. The phone uploads the bytes straight to R2.
3. `POST /api/files/{id}/complete` makes the server confirm the object exists with the declared size. For volunteer documents it also ticks the matching item on the application.
4. `GET /api/files/{id}/download-url` gives the **owner only** a 5-minute GET link. `DELETE /api/files/{id}` removes the object.

Notes:
- **Object keys** are `kind/<internal user id>/<random id>`: no names, phone numbers or original file names.
- **Accepted types:** PDF/JPEG/PNG/WebP for volunteer documents; JPEG/PNG/WebP for photos; common audio types for voice notes. Maximum size is `MAX_UPLOAD_BYTES` (10 MB).
- **Nothing is written to the server's disk.**
- **Not yet in the app:** the screens that pick and upload files (04 volunteer documents, 08 voice/photo evidence, 14 profile photo). The server side is ready.

## 4. Keep-alive with cron-job.org

1. Sign up at [cron-job.org](https://cron-job.org) and choose **Create cronjob**:
   - **URL:** `https://surakshanet-api.onrender.com/ready`
   - **Schedule:** every **10 minutes**. That's under Render's 15-minute spin-down, and it never wakes Neon.
   - **Request method:** GET.
   - **Advanced → Timeout:** 30 s. The very first ping after a deploy may take up to a minute.
   - **Notifications:** on failure, sent to your email.
2. Save, then check **History** after 20 minutes: both runs should be HTTP 200.
3. This uses about 744 of the 750 free Render hours each month. If you add any other free Render service, the hours run out before the month ends and Render suspends both.

## 5. Redis is optional

The free stack runs **without Redis**. With `REDIS_URL` unset, realtime events and WebSocket tickets stay inside the single API process. That's correct because `start-render.sh` always starts **one** uvicorn worker, and it logs `[startup] REDIS_URL not set; using the in-memory realtime bus (single worker)`.
- `render.yaml` no longer creates the Render Key Value instance. If one already exists from the old Blueprint, delete **surakshanet-kv** in the Render dashboard. A Blueprint sync doesn't delete it for you.
- You only need Redis to run more than one worker or instance. Set `REDIS_URL` (e.g. a free Upstash Redis, `rediss://…`) and raise the worker count.

## 6. Environment variables (Render → surakshanet-api → Environment)

| Variable | Required | Value |
|---|---|---|
| `DATABASE_URL` | yes | Neon connection string (secret) |
| `FIREBASE_PROJECT_ID` | yes | `surakshanet-c4ec1` (in `render.yaml`) |
| `GOOGLE_APPLICATION_CREDENTIALS` | yes | `/etc/secrets/firebase-admin.json`, uploaded under **Secret Files** |
| `ENVIRONMENT` | yes | `beta` for the friends beta: any verified account may use Maitri and SOS is real. `development` keeps the test-account allowlist. The pre-beta code on `master` doesn't accept `beta`: set `development` before deploying older code |
| `SOS_PER_HOUR` | no | Real SOS per account per hour, default `3`. Raised to 20 only while testing; **remove it before inviting testers** |
| `LIVE_LINK_SECRET` | beta | Any long random string (`python -c "import secrets; print(secrets.token_urlsafe(32))"`). Signs `/l/...` live-location links so they survive restarts |
| `PUBLIC_URL` | beta | The service URL, e.g. `https://surakshanet-api.onrender.com` (no trailing slash). Used in live links |
| `MEDICAL_NOTES_KEY` | beta | 32 random bytes, base64 (`python -c "import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"`). Encrypts medical notes. **Keep a copy**: losing or changing it makes saved notes unreadable |
| `SENTRY_DSN` | no | Sentry project DSN (FastAPI platform). Errors only, scrubbed of personal data. Unset = no reporting |
| `SENTRY_TEST` | no | `1` sends one test message to Sentry at startup, to check reports arrive. Remove it afterwards |
| `RESPONDERS_NEEDED` | no | Volunteers needed per SOS. `0` (default) = no limit. With N, once N have accepted, the other open requests close ("Help is already on the way") |
| `FIREBASE_WEB_API_KEY` | for `/admin` | Firebase console → Project settings → Your apps → Web app → `apiKey`. Also add the Render host under Authentication → Settings → Authorized domains |
| `TEST_ACCOUNT_UIDS` | development | JSON array of allowed test UIDs (secret). Ignored when `ENVIRONMENT=beta` |
| `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | for uploads | From step 3 (secrets). Unset = uploads return 503 |
| `R2_ENDPOINT` | no | Only for a non-R2 S3 store |
| `REDIS_URL` | no | Leave unset on the free stack |
| `DB_IDLE_SECONDS` / `IDLE_SWEEP_SECONDS` | no | `900` / `3600` |
| `MAX_UPLOAD_BYTES`, `UPLOAD_URL_SECONDS`, `DOWNLOAD_URL_SECONDS` | no | `10485760`, `300`, `300` |
| `MAX_SOS_ACCURACY_METERS`, `MAX_LAST_KNOWN_AGE_SECONDS`, `ORPHANED_INCIDENT_HOURS` | no | `5000`, `600`, `12` |

## 7. Deploying a change

1. Push the branch the service deploys (**Settings → Build & Deploy → Branch**; `beta-complete` during the beta,
   `master` after the merge), then **surakshanet-api → Manual Deploy → Deploy latest commit** (auto-deploy is off).
   When you add or change environment variables, use **Save only**, then deploy once.
2. Watch **Logs** for the `[startup]` lines: configuration → migrations → `starting FastAPI`. A migration error stops startup before the API takes traffic.
3. Check `/ready`, then sign in on a phone.
4. **Rollback:**
   - **Code:** in **Events**, pick the previous deploy → **Rollback**.
   - **Schema:** migrations are additive. 0013-0017 (beta) only add tables and columns and relax one constraint,
     so the pre-beta code still runs on them; switching back only needs `ENVIRONMENT=development`.
     To undo 0012 only, run `alembic downgrade 0011` against the database; this drops `stored_files`. The R2 objects stay until deleted.

## 8. One-time setup for the beta

1. **Admin reviewer:** Firebase console → Authentication → Users → copy your **User UID**; Project settings →
   Service accounts → **Generate new private key** (save it outside the repo). Then
   `cd backend` and `.venv\Scripts\python.exe scripts\set_admin_claim.py --key <path-to-key.json> --uid <UID>`.
   Sign out of `/admin` and in again.
2. **Crash reports:** Firebase console → Crashlytics → **Enable**. Release builds report crashes unless the user
   turns it off in Privacy & data.
3. **Public pages:** after the merge to `master`, GitHub → Settings → Pages → Source: **GitHub Actions**. The privacy
   policy, terms and account-deletion pages in `docs/site` are then published by `.github/workflows/pages.yml`.

## What users see while the server wakes

- **"Connecting to Maitri…":** shown on Home and screen 08 when any API call has taken longer than 3 seconds. It clears when the server answers.
- **SOS retries:** the SOS keeps retrying in the background, through WorkManager with exponential backoff for up to about 3 hours. Screen 08 shows the phone's own location ("Sending…"). The newest fix, plus up to 2 minutes of queued ones, are sent once the server answers.
- **Call 112:** pinned on every screen while an SOS is active, independent of the server.
