#!/bin/sh
set -eu
exec alembic -x ensure_postgis=true upgrade head
