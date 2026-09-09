#!/bin/sh
# Third-party notices for the operating-system packages in this image.
#
# Run inside the runtime stage, after everything has been installed, and its
# output is appended to /app/THIRD-PARTY.txt. It has to run *there* rather than
# in Gradle for the obvious reason: the package set is a property of the image,
# not of the source tree, and it differs per architecture — this image is built
# for amd64 and arm64, and the runtime stage of each has its own package list.
#
# Why this exists at all: an image is a binary distribution, so the licences of
# what it redistributes travel with it. That is the same argument
# api/build.gradle.kts makes about BOOT-INF/lib, and it does not stop at the jar.
# The jar's own trees are Parts 1 and 2; this is Part 3.
#
# Source of truth is apk's installed database, so this cannot drift from what is
# actually in the image the way a hand-maintained list would.
set -eu

DB=/lib/apk/db/installed
[ -r "$DB" ] || { echo "os-notices: $DB not readable" >&2; exit 1; }

ALPINE=$(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-Alpine Linux}")
ARCH=$(apk --print-arch 2>/dev/null || uname -m)
COUNT=$(grep -c '^P:' "$DB")

echo
echo "=============================================================================="
echo "PART 3 — OPERATING SYSTEM PACKAGES (the container image)"
echo "=============================================================================="
echo
cat <<'TEXT'
Parts 1 and 2 cover what is inside the application jar. This part covers the rest
of the image: the base operating system, the Java runtime's own dependencies, and
the packages installed for booking import — KItinerary, and the Qt, poppler and
ZXing stack it is built on.

Two things about this list are worth stating plainly rather than leaving to be
discovered.

**It names licences; it does not reproduce their texts.** Alpine packages do not
ship per-package licence or copyright files, unlike Debian's
/usr/share/doc/*/copyright, so there is nothing in the image to copy here. What
is given instead is, for every package, the SPDX identifier Alpine records and
the upstream project it came from — which is what the text can be obtained from.

**Copyleft components are present, and this is the offer of source for them.**
The list below includes packages under the GPL, LGPL and MPL. Every one of them
is built by Alpine Linux from a recipe in the aports repository, and both the
recipe and the upstream tarball it fetches are public:

  aports (build recipes):   https://gitlab.alpinelinux.org/alpine/aports
  package index and source: https://pkgs.alpinelinux.org/packages
  upstream:                 the URL given against each package below

Nothing in this image is a modified build of any of them — they are Alpine's own
binary packages, installed unaltered — so the source corresponding to each is the
version-matched source in aports. If you rebuild this image with changes to any
of these packages, that offer becomes yours to make rather than ours.

Note also what this does *not* affect. wander itself is AGPL-3.0-or-later. These
packages are separate programs that happen to share a filesystem with it, not
code linked into it; the application talks to KItinerary by running it as a
subprocess and reading its output. An image is an aggregate of independent works,
which is why a GPL-2.0-only utility can sit beside an AGPL-3.0 application here
without either licence reaching the other.
TEXT
echo
echo "Image:     $ALPINE ($ARCH)"
echo "Packages:  $COUNT"
echo
echo "Format: package  version  —  licence  —  upstream"
echo "------------------------------------------------------------------------------"
echo

# One record per package. `P:` opens a record, so the previous one is flushed
# when the next begins — and again at EOF, or the last package would be dropped.
#
# Each record is emitted as a single tab-separated line and only *then* sorted.
# Sorting the formatted three-line blocks instead sorts their lines
# independently, which silently pairs every package name with somebody else's
# licence — a plausible-looking file that is wrong throughout.
awk -F'\t' '
  function flush() {
    if (p != "") {
      printf "%s\t%s\t%s\t%s\n", p, v,
             (l == "" ? "licence not recorded by apk" : l),
             (u == "" ? "(no upstream URL recorded)" : u)
    }
    p = ""; v = ""; l = ""; u = ""
  }
  /^P:/ { flush(); p = substr($0, 3); next }
  /^V:/ { v = substr($0, 3); next }
  /^L:/ { l = substr($0, 3); next }
  /^U:/ { u = substr($0, 3); next }
  END   { flush() }
' "$DB" | sort -f | awk -F'\t' '{ printf "%s %s\n    %s\n    %s\n\n", $1, $2, $3, $4 }'

echo "------------------------------------------------------------------------------"
echo "Generated from $DB at image build time by deploy/os-notices.sh."
