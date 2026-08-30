#!/bin/sh
# Pull the current image and restart onto it. Safe to re-run; it is the whole
# update procedure.
#
#   ./update.sh
#
# Flyway migrates on boot, so an update carrying schema changes applies them as
# the new container starts, and a migration is not reversible by restarting the
# old image. The backup sidecar runs on a timer rather than on demand, so if you
# want a way back that is newer than last night's dump, take one by hand first:
#
#   docker compose exec -T db pg_dump -Fc -U wander wander > pre-update.dump
set -e

cd "$(dirname "$0")"

if [ ! -f .env ]; then
	echo "No .env here. Copy .env.example to .env and edit it first." >&2
	exit 1
fi

# --no-build, because a deployment has no source: compose.yaml still names a
# build context so that a working copy can build from it, and this says plainly
# that this machine never will.
docker compose pull
docker compose up -d --no-build

# The old image is now unreferenced and is a few hundred megabytes. Only
# dangling ones — this never touches an image something still uses.
docker image prune -f

docker compose ps
