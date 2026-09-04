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
COPY --from=build /src/api/build/libs/*-SNAPSHOT.jar /app/wander.jar

# The licences of everything redistributed inside that jar — the browser bundle
# under META-INF/resources and the server's own jars under BOOT-INF/lib.
#
# It ships *in the image* because the image is the distribution: MIT, BSD, ISC
# and Apache-2.0 all require the copyright notice to accompany a binary, and an
# image is a binary. Generated at build time from the two dependency trees, so
# it cannot fall behind them the way a committed copy would. wander's own
# licence is separate and is not this file — see LICENSE in the repository, and
# the Source link the running instance draws for AGPL section 13.
COPY --from=build /src/build/THIRD-PARTY.txt /app/THIRD-PARTY.txt

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
