FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Repo-root build context (issue #896): backend/settings.gradle.kts includes the sibling
# opaa-api/ module by relative path, so both directory trees must exist under /app in the same
# layout as the repo, not just backend/'s own contents.
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties backend/
COPY backend/gradle/ backend/gradle/
COPY backend/gradlew backend/
COPY opaa-api/build.gradle.kts opaa-api/
WORKDIR /app/backend
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
WORKDIR /app
COPY backend/src/ backend/src/
COPY opaa-api/src/ opaa-api/src/
WORKDIR /app/backend
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre AS runtime
# Without this the image ships the Ubuntu security state of the day Temurin last rebuilt its base
# (issue #1450); publish-images.yml keeps this stage out of the layer cache so the update happens
# on every publish. pebble is the service manager of Canonical's Ubuntu rock base and is never
# started here - removed instead of patched; the bare `rm` is deliberate, a moved or dropped
# binary has to break the build rather than silently reinstate its Go runtime.
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get dist-upgrade -y; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*; \
    rm /usr/bin/pebble
WORKDIR /app
COPY --from=build /app/backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
