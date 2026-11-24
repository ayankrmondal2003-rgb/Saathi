#!/bin/sh
set -eu
cd /app
printf '%s\n' '[startup] Checking backend configuration'
: "${DATABASE_URL:?DATABASE_URL is missing}"
: "${FIREBASE_PROJECT_ID:?FIREBASE_PROJECT_ID is missing}"
if [ "${RENDER:-}" = true ]; then
    : "${GOOGLE_APPLICATION_CREDENTIALS:?Firebase secret file path is missing}"
fi
# Redis is optional. Without it the realtime bus and WebSocket tickets live in this process, which is only
# correct with exactly one worker: the uvicorn command below always starts one.
if [ -z "${REDIS_URL:-}" ]; then
    printf '%s
' '[startup] REDIS_URL not set; using the in-memory realtime bus (single worker)'
fi
if [ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ] && [ ! -r "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
    printf '%s\n' '[startup] Firebase secret file is unreadable' >&2
    exit 78
fi
if sh ./render-migrate.sh; then
    printf '%s\n' '[startup] Migrations complete; starting FastAPI'
else
    status=$?
    printf '[startup] Migration failed (exit %s); API startup stopped\n' "$status" >&2
    exit "$status"
fi
exec uvicorn app.main:create_app --factory --host 0.0.0.0 --port "${PORT:-8000}" --workers 1 --log-level warning --no-access-log
