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

# Custom Java runtime (ADR-0029, issue #1459). java.se is the whole Java SE API including
# java.desktop, which PDFBox and POI require; the jdk.* modules below are service providers the
# JDK loads reflectively, so no dependency analysis derives them: TLS ciphers (crypto.ec,
# crypto.cryptoki), non-UTF-8 charsets of imported documents (charsets), German formatting
# (localedata), DNS/JNDI (naming.dns), sun.misc.Unsafe used by Hibernate and Spring (unsupported),
# ZIP filesystem access (zipfs), and the diagnostic surface an operator has left on a shell-less
# image (jfr, management.agent, jdwp.agent). A missing module surfaces only at runtime; the E2E
# suite, not the build, is what keeps this list honest.
FROM eclipse-temurin:21-jdk AS jlink
RUN "$JAVA_HOME/bin/jlink" \
    --add-modules java.se,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.dynalink,jdk.jdwp.agent,jdk.jfr,jdk.localedata,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.net,jdk.security.auth,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
    --output /javaruntime

# Distroless ships glibc without any of the packages the triage in #1459 named: no fontconfig (and
# with it no libexpat), no coreutils, no p11-kit, no tar, wget, gnupg or perl, no pebble. The
# runtime above is self-contained - cacerts, time-zone database and locale data come from the JDK,
# not from OS packages - so the base has to provide no more than the C library. The -nossl variant
# over plain base for the same reason: the JVM brings its own TLS stack and never links libssl.
FROM gcr.io/distroless/base-nossl-debian13 AS runtime
# glibc reports the POSIX charset ANSI_X3.4-1968 without a compiled locale, which the JVM adopts as
# sun.jnu.encoding - file *names* carrying umlauts (a document library's normal case) would then be
# unreadable. Distroless strips /usr/lib/locale, so the one built-in locale is copied back in;
# language and country are pinned to the values the Temurin base used to export via LANG, so the
# default Locale stays en_US across this change.
COPY --from=debian:13-slim /usr/lib/locale/C.utf8 /usr/lib/locale/C.utf8
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jlink /javaruntime ${JAVA_HOME}
WORKDIR /app
COPY --from=build /app/backend/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-Duser.language=en", "-Duser.country=US", "-jar", "/app/app.jar"]
