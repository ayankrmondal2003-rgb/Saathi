#!/bin/sh
set -eu

echo "Running database migrations..."
sh render-migrate.sh

echo "Starting Maitri FastAPI..."
exec uvicorn app.main:create_app \
  --factory \
  --host 0.0.0.0 \
  --port "${PORT:-10000}" \
  --workers 1 \
  --log-level warning \
  --no-access-log