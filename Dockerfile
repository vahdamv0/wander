# Two stages, one artifact: the Angular build ends up inside the boot jar, so a
# deployment is a single container with no separate web server.

# --- build -----------------------------------------------------------------
# Debian rather than Alpine: the Gradle node plugin downloads glibc node
# binaries, which do not run on musl.
#
# --platform=$BUILDPLATFORM pins this stage to the *builder's* architecture,
# which is what makes a multi-architecture image affordable here. What this
# stage produces — a jar with the Angular build inside it — is bytecode and
# static files, identical whatever CPU compiled them, so building it once
# natively and copying it into each runtime is not a shortcut but the correct
# answer. Without the flag, buildx would run Gradle and npm under QEMU to
# produce a byte-identical jar, turning a five-minute build into most of an
# hour.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build scripts and the lockfile first, so editing source does not re-resolve
# every Gradle and npm dependency.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY api/build.gradle.kts api/
COPY web/build.gradle.kts web/
COPY web/package.json web/package-lock.json web/
RUN ./gradlew --no-daemon :web:npmInstall

COPY api api
COPY web web
# No tests here: they need Postgres in a container, which an image build has no
# business starting. CI runs them, and the generated API client is committed, so
# this build needs nothing but source.
#
# thirdPartyNotices rides along in the same invocation rather than getting its
# own RUN: it shares the resolved Gradle and npm caches with the build above, so
# as a separate layer it would re-resolve both to produce one text file.
RUN ./gradlew --no-daemon :api:bootJar thirdPartyNotices

# --- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S wander && adduser -S -G wander wander
WORKDIR /app

# KItinerary, the engine behind booking import. It is a command-line program, so
# nothing here is linked or generated — the application execs it and reads
# schema.org JSON-LD off stdout, the relationship backup.sh has with pg_dump.
# What it buys is 349 provider extractors (airlines, railways, hotel chains, and
# the white-label platforms small hotels run on), schema.org JSON-LD *and*
# microdata, Apple Wallet passes, boarding-pass barcodes, and its own airport
# database — which is why nothing in this project ships a table of IATA codes.
#
# It roughly triples the image: 282MB to 737MB unpacked, and 134MB to 291MB
# compressed, which is the number that matters for a pull — +157MB to fetch.
# Measured on amd64, and measured rather than estimated: the compressed figures
# are the layer sizes in the image manifest, the unpacked ones the sum of the
# layer diffs. (`docker image ls` disagrees with both, reporting the unpacked
# snapshot; it is not the number to quote.) The reason it is still worth it is
# that the alternative is a hand-written parser competing with 349 maintained
# ones. The runtime cost is small and bounded — ~90MB peak resident for ~170ms,
# per import, on this hardware.
#
# Two caveats worth knowing before editing this block:
#
#  - **Do not delete Mesa or LLVM to save the 225MB they cost.** libEGL and
#    libGL are hard-linked by Qt6Gui and pull libgallium in at process start, so
#    removing them does not trim the image, it stops the extractor dead with
#    "Error loading shared library libgallium". The Breeze icon theme below is
#    different: it is data nothing links, so removing it is free and verified.
#  - **The package is in Alpine `community`, not `main`, and this base tag
#    floats.** A future Temurin rebase onto an Alpine that renames or drops it
#    breaks this build. That failure is a red pipeline rather than a bad deploy,
#    which is why the tag is not pinned to a digest here — but it is the reason
#    to look at this line first if the image build fails for no other reason.
#
# The feature degrades rather than breaking if this is removed: the iCalendar
# reader is pure Java and always present, so an image without the extractor
# reports itself as calendar-only. See wander.booking-import in application.yml.
# poppler-data is not optional, and costs 13MB unpacked or 4MB compressed. It
# carries the CMap tables for the CJK character collections — Adobe-Japan1 and
# its siblings — and without them poppler cannot decode the *text* of a PDF
# that uses one. What that looks like from here is not an error: extraction
# runs, every matching script is offered the document, and `pdf.pages[n].text`
# is an empty string, so each one returns nothing and the import answers
# "nothing recognised". A JAL or ANA e-ticket is exactly such a document, which
# makes this the difference between reading a Japanese airline's confirmation
# and quietly never reading one.
RUN apk add --no-cache kitinerary poppler-data && \
    ln -sf /usr/lib/libexec/kf6/kitinerary-extractor /usr/local/bin/kitinerary-extractor && \
    rm -rf /usr/share/icons /usr/lib/libKF6BreezeIcons.so* /usr/share/fonts /usr/share/X11

# Both are load-bearing and neither is obvious. Qt probes for a display unless
# told the platform is offscreen, and KF6 wants a writable cache directory —
# which the `wander` user does not have, having no home. Get the second wrong and
# extraction fails for reasons that have nothing to do with the document.
ENV QT_QPA_PLATFORM=offscreen \
    XDG_CACHE_HOME=/tmp/kf6-cache
COPY --from=build /src/api/build/libs/*-SNAPSHOT.jar /app/wander.jar

# Fixes to upstream extractors, loaded by kitinerary-extractor from this path.
# Real files rather than jar resources, because the consumer is a subprocess that
# reads a directory. See extractors/README.md for the bar a file here has to
# clear — it is a narrow exception to "the parsing is borrowed, not written", and
# each file is a patch waiting for upstream to take it.
COPY extractors /app/extractors
ENV WANDER_IMPORT_EXTRACTOR_SEARCH_PATH=/app/extractors

# The licences of everything this image redistributes, in three parts.
#
# It ships *in the image* because the image is the distribution: MIT, BSD, ISC
# and Apache-2.0 all require the copyright notice to accompany a binary, and an
# image is a binary. wander's own licence is separate and is not this file — see
# LICENSE in the repository, and the Source link the running instance draws for
# AGPL section 13.
#
# Parts 1 and 2 are the jar's two dependency trees — the browser bundle under
# META-INF/resources and the server's jars under BOOT-INF/lib — generated by
# Gradle from the trees themselves, so they cannot fall behind the way a
# committed copy would.
#
# **Part 3 is the rest of the image**, and it has to be produced here rather than
# in Gradle: the package set is a property of the runtime stage, not of the
# source tree, and it differs per architecture — this image is built for amd64
# and arm64 and each runtime stage has its own list. Booking import is what made
# this section large (73 packages to 233), though it was never empty: the base
# image has always carried a GPL-3 coreutils and gnupg, so the obligation
# predates that feature and only grew with it.
COPY --from=build /src/build/THIRD-PARTY.txt /app/THIRD-PARTY-jar.txt
COPY deploy/os-notices.sh /app/os-notices.sh
RUN sh /app/os-notices.sh >> /app/THIRD-PARTY-jar.txt && \
    mv /app/THIRD-PARTY-jar.txt /app/THIRD-PARTY.txt

# What a server needs beside the image, carried inside it: `docker run --rm
# <image> bundle | tar x` writes these out. They are copied from the repository
# at build time, so the compose file a deployment runs is the one committed
# alongside the image it runs — there is no second copy to drift.
COPY compose.yaml Caddyfile .env.example /app/deploy/
COPY backup/backup.sh /app/deploy/backup/
COPY deploy/DEPLOY.md deploy/update.sh /app/deploy/
COPY deploy/entrypoint.sh /app/entrypoint.sh

# What this image says it is, both halves passed by CI and drawn together in the
# account menu — so "which build is that instance on" has an answer that does not
# need SSH.
#
# WANDER_VERSION is the release tag, and only a tag pipeline sets it: an image
# built from an ordinary commit on main is honestly "dev", because it is not a
# release. WANDER_BUILD_REF is the commit, which is the half that moves on every
# build and the only thing that identifies an instance updated by pulling
# `latest`. Blank build ref means a build made outside CI, and blank is drawn as
# nothing rather than as "unknown".
ARG WANDER_VERSION=dev
ENV WANDER_VERSION=${WANDER_VERSION}
ARG WANDER_BUILD_REF=""
ENV WANDER_BUILD_REF=${WANDER_BUILD_REF}

USER wander
EXPOSE 8080
# Container-aware heap sizing: the JVM otherwise reads the host's memory, not
# the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["/app/entrypoint.sh"]
