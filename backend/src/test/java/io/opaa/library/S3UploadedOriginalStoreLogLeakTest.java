package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.source.s3.StubS3Server;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The upload storage's credentials appear in no log line of the adapter, the shared S3 layer, the
 * SDK or the HTTP client at DEBUG - asserted on the rendered output, throwable text included,
 * across the paths that log at all: a refused put, a refused download, a missing object, a failed
 * deletion, the startup probe against a dead endpoint. The same pins {@code S3LogLeakTest} relies
 * on keep the signer and the wire log quiet here too.
 */
class S3UploadedOriginalStoreLogLeakTest {

  private static final String ACCESS_KEY = "AKIAUPLOADLEAKTEST";
  private static final String SECRET_KEY = "hochgeheimer-upload-secret-4711";

  private static final Map<String, Level> WATCHED =
      Map.of(
          "io.opaa.library",
          Level.TRACE,
          "io.opaa.indexing.source.s3",
          Level.TRACE,
          "io.opaa.sourceaccess",
          Level.TRACE,
          "software.amazon.awssdk",
          Level.DEBUG,
          "org.apache.hc.client5",
          Level.DEBUG,
          "software.amazon.awssdk.http.auth.aws.internal.signer",
          Level.INFO,
          "org.apache.hc.client5.http.headers",
          Level.INFO,
          "org.apache.hc.client5.http.wire",
          Level.INFO);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();
  private StubS3Server server;

  @TempDir java.nio.file.Path tempDir;

  @BeforeEach
  void attach() throws Exception {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    WATCHED.forEach(
        (name, level) -> {
          Logger logger = (Logger) LoggerFactory.getLogger(name);
          previousLevels.put(logger, logger.getLevel());
          logger.setLevel(level);
          logger.addAppender(appender);
        });
    server = new StubS3Server();
    server.addBucket("ablage");
  }

  @AfterEach
  void detach() {
    previousLevels.forEach(
        (logger, level) -> {
          logger.detachAppender(appender);
          logger.setLevel(level);
        });
    server.close();
  }

  @Test
  void logsCarryNeitherSecretKeyNorAccessKey() throws Exception {
    UploadS3Properties properties =
        new UploadS3Properties(
            server.endpoint(),
            null,
            "ablage",
            null,
            true,
            ACCESS_KEY,
            SECRET_KEY,
            tempDir,
            new UploadS3Properties.TargetValidation(true, List.of()));
    UUID libraryId = UUID.randomUUID();

    try (S3UploadedOriginalStore store =
        new S3UploadedOriginalStore(
            properties,
            UploadS3TargetPolicy.of(properties),
            java.time.Duration.ofSeconds(3),
            1,
            java.time.Duration.ofMillis(1),
            S3UploadedOriginalStore.LIST_PAGE_SIZE)) {
      UploadedOriginalStore.AcceptedUpload accepted =
          store.accept(
              libraryId, ".pdf", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
      UploadedOriginalRef ref = accepted.store();
      accepted.release();

      // a refused HeadObject is "not there" (see S3UploadedOriginalStore#resolve), a refused
      // GetObject is "unavailable" - both paths log
      server.failNext(403, "InvalidAccessKeyId", 1);
      assertThat(store.openForDownload(ref, "x.pdf", null)).isEmpty();
      server.failNextMatching("GET", "/ablage/", 403, "AccessDenied");
      assertThatThrownBy(() -> store.withLocalFile(ref, p -> "x"))
          .isInstanceOf(UploadStoreUnavailableException.class);
      server.failNextMatching("DELETE", "/ablage/", 403, "AccessDenied");
      store.delete(ref);
      assertThat(
              store.openForDownload(
                  new UploadedOriginalRef(libraryId, ref.locator() + "x"), "x", null))
          .isEmpty();
      UploadedOriginalStore.AcceptedUpload refused =
          store.accept(
              libraryId, ".pdf", new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)));
      server.failNextMatching("PUT", "/ablage/", 403, "AccessDenied");
      assertThatThrownBy(refused::store).isInstanceOf(java.io.IOException.class);
      refused.discard();
      server.close();
      store.recoverAfterRestart();
    }

    List<String> rendered =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : " " + event.getThrowableProxy().getMessage()))
            .toList();
    assertThat(rendered).as("something was logged at all").isNotEmpty();
    assertThat(rendered)
        .noneSatisfy(line -> assertThat(line).contains(SECRET_KEY))
        .noneSatisfy(line -> assertThat(line).contains(ACCESS_KEY))
        .noneSatisfy(line -> assertThat(line).contains("Signature="));
  }
}
