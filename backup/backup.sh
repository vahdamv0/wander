#!/bin/sh
# Takes a dump, checks it can be read back, publishes it, drops the oldest.
#
# Runs in the *same* image as the database, deliberately: pg_dump refuses to dump
# a server newer than itself, so a sidecar pinned to some other postgres version
# is a backup that stops working the day you upgrade.
#
# Four things here are the difference between a backup and the belief in one:
#
#  - **It writes to a temporary name and renames.** A rename within a filesystem
#    is atomic, so a reader — or a copy job pulling files off the box — never
#    sees a half-written dump and mistakes it for a good one.
#  - **It reads every dump back before publishing it.** `pg_restore --list` walks
#    the archive's table of contents, so a truncated or corrupt file is caught
#    here rather than on the worst day of the year. A dump nobody has ever read
#    is a file, not a backup.
#  - **A failure does not end the loop.** One bad night (the database restarting,
#    the disk briefly full) must not silently stop every backup after it, which
#    is what `set -e` around the dump would do.
#  - **Retention counts only *published* dumps.** Failed attempts are removed, so
#    a run of failures can never rotate away the good copies that came before.
#
# What this does NOT protect against: losing the machine. These dumps sit on the
# same host as the database they came from. See README for the one line that
# copies them somewhere else, which is the half that survives a dead disk.

set -u

BACKUP_DIR="${WANDER_BACKUP_DIR:-/backups}"
INTERVAL="${WANDER_BACKUP_INTERVAL_SECONDS:-86400}"
KEEP="${WANDER_BACKUP_KEEP:-30}"

log() {
	echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"
}

mkdir -p "$BACKUP_DIR"

# A dump interrupted partway — a restart, a deploy, the host rebooting — leaves
# its temporary file behind, and nothing else ever removes it. They are dotfiles,
# so they do not show up in a casual `ls` and would accumulate quietly until the
# disk filled. Sweeping at startup is safe: this is the only writer, so anything
# matching here belongs to an attempt that is definitively over.
for stale in "$BACKUP_DIR"/.partial-*.dump; do
	[ -e "$stale" ] || continue
	log "clearing an interrupted dump: $(basename "$stale")"
	rm -f "$stale"
done

log "starting: every ${INTERVAL}s, keeping ${KEEP}, into ${BACKUP_DIR}"

while :; do
	stamp="$(date -u +%Y%m%dT%H%M%SZ)"
	partial="${BACKUP_DIR}/.partial-${stamp}.dump"
	final="${BACKUP_DIR}/wander-${stamp}.dump"

	# --format=custom, not plain SQL: it is compressed, and pg_restore can read
	# one table out of it without replaying the whole file.
	if pg_dump --format=custom --compress=9 --file="$partial" 2>&1; then
		if pg_restore --list "$partial" >/dev/null 2>&1; then
			mv "$partial" "$final"
			log "wrote $(basename "$final") ($(du -h "$final" | cut -f1))"

			# Only reached after a good dump, so a bad night never rotates.
			# shellcheck disable=SC2012
			ls -1t "${BACKUP_DIR}"/wander-*.dump 2>/dev/null \
				| tail -n +"$((KEEP + 1))" \
				| while read -r old; do
					log "removing $(basename "$old")"
					rm -f "$old"
				done
		else
			log "ERROR: the dump could not be read back, discarding it"
			rm -f "$partial"
		fi
	else
		log "ERROR: pg_dump failed, keeping the previous backups"
		rm -f "$partial"
	fi

	sleep "$INTERVAL"
done
