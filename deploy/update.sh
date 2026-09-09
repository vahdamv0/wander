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

# --- the bundle travels with the image -------------------------------------
#
# `.env` holds values; compose.yaml decides which of them reach the container.
# A release that adds a setting adds a line to both, so a server still on the
# old compose.yaml reads the new variable out of .env and passes it nowhere —
# one feature missing, and nothing in any log to say so. Hence re-extracting
# between the pull and the restart.
#
# Every file here came out of the image and is replaced whole: the Caddyfile
# takes its address from $WANDER_SITE_ADDRESS and has nothing in it to
# customise, and the same goes for compose.yaml, backup/backup.sh, DEPLOY.md
# and this script. **.env is never written**, so settings are not at risk, and
# anything that had been edited is kept in bundle-backup-*.
#
# The image is asked of compose rather than read out of .env, because compose
# is what will actually start it: that honours ${WANDER_IMAGE:-wander:local},
# a quoted value and a trailing comment, none of which a grep over .env gets
# right. It is read out of the normalised config — `--images wander` looks like
# the obvious call and is the wrong one, because it also lists the service's
# *dependencies*, so on this file it answers with Postgres as often as not and
# in no fixed order.
image=$(docker compose config | awk '
	/^  [a-zA-Z0-9_-]+:$/ { svc = $1; sub(/:$/, "", svc) }
	svc == "wander" && /^    image: / { print $2; exit }')
if [ -z "$image" ]; then
	echo "Could not work out which image the wander service uses." >&2
	echo "Check that compose.yaml and .env are valid: docker compose config" >&2
	exit 1
fi

# Staged inside the project directory on purpose, so adopting a file is a
# rename within one filesystem. Two things follow, and both matter. A rename is
# atomic, so nothing is ever half-written; and it replaces the directory entry
# rather than the file, so this script — which the shell is still reading, by
# offset, from the inode it opened — goes on reading the old copy to the end.
# Copying over the target instead would truncate a running script and the shell
# would execute whatever landed at its next read.
staged=$(mktemp -d ./.bundle.XXXXXX)
trap 'rm -rf "$staged"' EXIT

# Written to a file and then extracted, rather than piped straight into tar.
# A pipeline in POSIX sh reports only its *last* command's status, so a failing
# `docker run` down a pipe is invisible and tar is left to complain about an
# empty archive — which is what an operator would have to diagnose from. Split
# in two, each half can say what actually went wrong.
#
# Either way the run stops here: after the pull, before the restart, which is
# the safe half to stop in. The old container is still up and the files beside
# it still match the image it came from.
if ! docker run --rm "$image" bundle > "$staged/bundle.tar" 2> "$staged/bundle.err"; then
	sed 's/^/  /' "$staged/bundle.err" >&2
	echo "Could not read the deployment bundle out of $image." >&2
	echo "Nothing has been restarted; the running container is untouched." >&2
	exit 1
fi
if ! tar -xf "$staged/bundle.tar" -C "$staged"; then
	echo "The bundle in $image is not a readable tar archive." >&2
	echo "Nothing has been restarted; the running container is untouched." >&2
	exit 1
fi
# Out of the way before anything walks the staging directory looking for files
# the bundle brought.
rm -f "$staged/bundle.tar" "$staged/bundle.err"

backup="bundle-backup-$(date +%Y%m%d-%H%M%S)"
changed=""
self_changed=""
# Read line by line rather than `for f in $(find)`, which would split a path
# with a space in it into two. The list is fed in as a here-document rather
# than down a pipe, because a piped `while` runs in a subshell and the flags
# this loop sets would not survive it.
files=$(cd "$staged" && find . -type f | sed 's|^\./||' | sort)
while IFS= read -r file; do
	[ -n "$file" ] || continue
	cmp -s "$staged/$file" "$file" 2>/dev/null && continue
	if [ -f "$file" ]; then
		mkdir -p "$backup/$(dirname "$file")"
		cp -p "$file" "$backup/$file"
	fi
	mkdir -p "$(dirname "$file")"
	mv "$staged/$file" "$file"
	# tar drops nothing a non-root extraction needs, but the two scripts are
	# useless without the bit and re-adding it costs a syscall.
	case "$file" in *.sh) chmod +x "$file" ;; esac
	echo "bundle: updated $file"
	changed=yes
	[ "$file" = "update.sh" ] && self_changed=yes
done <<EOF
$files
EOF

if [ -n "$changed" ]; then
	if [ -d "$backup" ]; then
		echo "bundle: previous copies are in $backup/ (delete it once you are happy)"
		# Worth saying only when .env.example actually moved. A setting that
		# appeared in this release is the thing an operator most needs to know
		# about, and it is otherwise found by reading a changelog nobody has.
		#
		# The whole report is wrapped so it cannot fail the run. It is a
		# courtesy; restarting onto the image that was just pulled is the job,
		# and a diff of two text files must never be what stops that happening.
		# LC_ALL=C is not decoration either: `comm` compares bytes and warns —
		# non-zero, which `set -e` would take as fatal — the moment `sort` has
		# used the locale's collation instead.
		if [ -f "$backup/.env.example" ]; then
			added=$(
				set +e
				settings() {
					sed -n 's/^#\{0,1\}\(WANDER_[A-Z0-9_]*\)=.*/\1/p' "$1" |
						LC_ALL=C sort -u
				}
				settings "$backup/.env.example" > "$staged/before"
				settings .env.example > "$staged/after"
				LC_ALL=C comm -13 "$staged/before" "$staged/after" 2>/dev/null
			) || added=""
			if [ -n "$added" ]; then
				echo "bundle: settings new in this release — see .env.example for"
				echo "bundle: what each does and whether it needs a value from you:"
				echo "$added" | sed 's/^/  /'
			fi
		fi
	fi
else
	echo "bundle: already current"
fi

docker compose up -d --no-build

# The old image is now unreferenced and is a few hundred megabytes. Only
# dangling ones — this never touches an image something still uses.
docker image prune -f

docker compose ps

# Last, so it is the line still on screen. The rest of this run was the old
# script: it had already been read.
if [ -n "$self_changed" ]; then
	echo
	echo "Note: update.sh changed in this release; this run used the previous"
	echo "version of it. The new one takes effect next time."
fi
