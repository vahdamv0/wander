# Two stages, one artifact: the Angular build ends up inside the boot jar, so a
# deployment is a single container with no separate web server.

# --- build -----------------------------------------------------------------
# Debian rather than Alpine: the Gradle node plugin downloads glibc node
# binaries, which do not run on musl.
FROM eclipse-temurin:21-jdk AS build
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
USER wander
EXPOSE 8080
# Container-aware heap sizing: the JVM otherwise reads the host's memory, not
# the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wander.jar"]
