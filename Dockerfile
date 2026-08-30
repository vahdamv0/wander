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
RUN ./gradlew --no-daemon :api:bootJar

# --- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S wander && adduser -S -G wander wander
WORKDIR /app
COPY --from=build /src/api/build/libs/*-SNAPSHOT.jar /app/wander.jar

# What a server needs beside the image, carried inside it: `docker run --rm
# <image> bundle | tar x` writes these out. They are copied from the repository
# at build time, so the compose file a deployment runs is the one committed
# alongside the image it runs — there is no second copy to drift.
COPY compose.yaml Caddyfile .env.example /app/deploy/
COPY backup/backup.sh /app/deploy/backup/
COPY deploy/DEPLOY.md deploy/update.sh /app/deploy/
COPY deploy/entrypoint.sh /app/entrypoint.sh

USER wander
EXPOSE 8080
# Container-aware heap sizing: the JVM otherwise reads the host's memory, not
# the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["/app/entrypoint.sh"]
