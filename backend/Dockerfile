FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Repo-root build context (issue #896): backend/settings.gradle.kts includes the sibling
# opaa-api/ module by relative path, so both directory trees must exist under /app in the same
# layout as the repo, not just backend/'s own contents.
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/
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

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
