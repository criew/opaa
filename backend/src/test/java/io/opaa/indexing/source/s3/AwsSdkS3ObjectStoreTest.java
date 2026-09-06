package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * {@link AwsSdkS3ObjectStore} against the {@link StubS3Server} (ADR-0027, Entscheidung 8 and 9):
 * pagination to the end, path-style and virtual-host request shapes, retries with backoff on {@code
 * 503 SlowDown}/{@code 429}, the byte ceiling while streaming, the request budget, and the mapping
 * of every failure to a German message that carries no credential.
 */
class AwsSdkS3ObjectStoreTest {

  private static final String ACCESS_KEY = "AKIASTUBACCESSKEY";
  private static final String SECRET_KEY = "hochgeheim/geheim+4711";
  private static final String SESSION_TOKEN = "session-token-0815";

  private StubS3Server server;
  private final List<S3ObjectStore> opened = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = new StubS3Server();
    server.addBucket("docs");
    server.putObject(
        "docs", "2025/a.pdf", "AAAA".getBytes(StandardCharsets.UTF_8), "application/pdf");
    server.putObject(
        "docs",
        "2025/b.docx",
        "BBBBBB".getBytes(StandardCharsets.UTF_8),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    server.putObject("docs", "2025/ordner/", new byte[0], "application/x-directory");
    server.putObject(
        "docs", "2024/c.pdf", "CC".getBytes(StandardCharsets.UTF_8), "application/pdf");
    server.putObject(
        "docs", "archiv/alt.pdf", "old".getBytes(StandardCharsets.UTF_8), "application/pdf");
  }

  @AfterEach
  void stop() {
    opened.forEach(S3ObjectStore::close);
    server.close();
  }

  private S3Properties properties(int pageSize, int retries, int budget) {
    return new S3Properties(
        pageSize, 0, Duration.ofSeconds(5), retries, Duration.ofMillis(1), budget);
  }

  private S3ObjectStore store(S3Properties properties, boolean pathStyle, boolean run)
      throws Exception {
    return store(properties, pathStyle, run, null, "http://127.0.0.1", false);
  }

  private S3ObjectStore store(
      S3Properties properties,
      boolean pathStyle,
      boolean run,
      java.util.function.Consumer<SdkHttpRequest> observer,
      String endpointBase,
      boolean sessionToken)
      throws Exception {
    URI endpoint = URI.create(endpointBase + ":" + URI.create(server.endpoint()).getPort());
    S3Credentials credentials =
        sessionToken
            ? new S3Credentials(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN)
            : new S3Credentials(ACCESS_KEY, SECRET_KEY, null);
    S3Connection connection =
        new S3Connection(endpoint, "us-east-1", pathStyle, credentials, null, -1, false);
    S3ClientFactory factory =
        new S3ClientFactory(properties, TargetAddressValidator.disabled(), observer);
    S3ObjectStore store =
        run ? factory.createForRun(connection, List.of()) : factory.create(connection, List.of());
    opened.add(store);
    return store;
  }

  @Test
  void listsAScopeToTheEndAcrossContinuationTokens() throws Exception {
    S3ObjectStore store = store(properties(2, 1, 0), true, false);
    S3Scope scope = S3Scope.of("docs", "2025");

    List<S3ObjectSummary> all = new ArrayList<>();
    String token = null;
    int pages = 0;
    do {
      S3ListPage page = store.listObjects(scope, token);
      all.addAll(page.objects());
      token = page.nextContinuationToken();
      pages++;
    } while (token != null);

    assertThat(pages).isEqualTo(2);
    assertThat(all)
        .extracting(S3ObjectSummary::key)
        .containsExactly("2025/a.pdf", "2025/b.docx", "2025/ordner/");
    S3ObjectSummary a = all.get(0);
    assertThat(a.eTag()).isEqualTo(StubS3Server.md5("AAAA".getBytes(StandardCharsets.UTF_8)));
    assertThat(a.size()).isEqualTo(4);
    assertThat(a.lastModified()).isNotNull();
    assertThat(a.storageClass()).isEqualTo("STANDARD");
    assertThat(a.isFolderMarker()).isFalse();
    assertThat(all.get(2).isFolderMarker()).isTrue();
    assertThat(server.seen()).allSatisfy(seen -> assertThat(seen.path()).isEqualTo("/docs"));
    assertThat(server.seen().get(0).query()).contains("prefix=2025%2F").contains("max-keys=2");
    assertThat(server.seen().get(1).query()).contains("continuation-token=t2");
    assertThat(store.meter().requests()).isEqualTo(2);
  }

  @Test
  void anArchivedObjectIsRecognisedFromTheListing() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);

    S3ListPage page = store.listObjects(S3Scope.of("docs", "archiv"), null);

    assertThat(page.objects()).singleElement().satisfies(o -> assertThat(o.isArchived()).isTrue());
  }

  @Test
  void pathStyleAddressesTheBucketInThePathAndVirtualHostInTheHost() throws Exception {
    AtomicReference<SdkHttpRequest> captured = new AtomicReference<>();
    S3ObjectStore pathStyle =
        store(properties(1000, 1, 0), true, false, captured::set, "http://localhost", false);
    pathStyle.headObject("docs", "2025/a.pdf");
    assertThat(captured.get().host()).isEqualTo("localhost");
    assertThat(captured.get().encodedPath()).isEqualTo("/docs/2025/a.pdf");

    // Virtual-host: the SDK addresses docs.localhost, which no test DNS resolves - the observer
    // records the signed request and aborts before transmission.
    AtomicReference<SdkHttpRequest> virtual = new AtomicReference<>();
    S3ObjectStore virtualHost =
        store(
            properties(1000, 1, 0),
            false,
            false,
            request -> {
              virtual.set(request);
              throw NonRetryableException.create("abgebrochen vom Test");
            },
            "http://localhost",
            false);
    assertThatThrownBy(() -> virtualHost.headObject("docs", "2025/a.pdf"))
        .isInstanceOf(S3AccessException.class);
    assertThat(virtual.get().host()).isEqualTo("docs.localhost");
    assertThat(virtual.get().encodedPath()).isEqualTo("/2025/a.pdf");
    assertThat(virtual.get().firstMatchingHeader("Authorization")).isPresent();
  }

  @Test
  void aSessionTokenIsSentAsSecurityTokenHeader() throws Exception {
    S3ObjectStore store =
        store(properties(1000, 1, 0), true, false, null, "http://127.0.0.1", true);

    store.headObject("docs", "2025/a.pdf");

    assertThat(server.seen().get(0).headers()).containsEntry("x-amz-security-token", SESSION_TOKEN);
    assertThat(server.seen().get(0).headers().get("authorization"))
        .contains("Credential=" + ACCESS_KEY);
  }

  @Test
  void headObjectReportsTypeAndTag() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);

    S3ObjectHead head = store.headObject("docs", "2025/a.pdf");

    assertThat(head.contentType()).isEqualTo("application/pdf");
    assertThat(head.eTag()).isEqualTo(StubS3Server.md5("AAAA".getBytes(StandardCharsets.UTF_8)));
    assertThat(head.archived()).isFalse();
  }

  @Test
  void downloadsIntoATemporaryFileTheCallerDeletes() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);

    S3Download download = store.getObject("docs", "2025/b.docx", 1024);
    try {
      assertThat(Files.readString(download.file())).isEqualTo("BBBBBB");
      assertThat(download.file().getFileName().toString()).endsWith(".docx");
      assertThat(download.contentType()).startsWith("application/vnd.openxmlformats");
      assertThat(download.size()).isEqualTo(6);
      assertThat(download.eTag())
          .isEqualTo(StubS3Server.md5("BBBBBB".getBytes(StandardCharsets.UTF_8)));
      assertThat(store.meter().bytesDownloaded()).isEqualTo(6);
    } finally {
      Files.deleteIfExists(download.file());
    }
  }

  @Test
  void theByteCeilingRejectsADeclaredOversizeBodyBeforeReadingAndAStreamedOneWhileReading()
      throws Exception {
    server.putObject("docs", "gross.bin", new byte[5000], "application/octet-stream");
    server.putObject("docs", "gross-chunked.bin", new byte[5000], "application/octet-stream");
    server.serveChunked("docs", "gross-chunked.bin");
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
    long before = countTempFiles(tempDir);

    assertThatThrownBy(() -> store.getObject("docs", "gross.bin", 4096))
        .isInstanceOf(S3AccessException.ObjectTooLarge.class)
        .hasMessageContaining("4096")
        .hasMessageContaining("docs/gross.bin");
    assertThatThrownBy(() -> store.getObject("docs", "gross-chunked.bin", 4096))
        .isInstanceOf(S3AccessException.ObjectTooLarge.class);

    assertThat(countTempFiles(tempDir)).as("partial files are removed").isEqualTo(before);
    assertThat(store.meter().bytesDownloaded()).isLessThan(5000);
  }

  private static long countTempFiles(Path dir) throws Exception {
    try (var files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().startsWith("opaa-s3-")).count();
    }
  }

  @Test
  void retriesSlowDownAndTooManyRequestsWithBackoffInsteadOfFailing() throws Exception {
    server.failNext(503, "SlowDown", 2);
    server.failNext(429, "TooManyRequests", 1);
    S3ObjectStore store = store(properties(1000, 5, 0), true, false);

    S3ListPage page = store.listObjects(S3Scope.of("docs", "2024"), null);

    assertThat(page.objects()).hasSize(1);
    assertThat(store.meter().requests()).isEqualTo(4);
    assertThat(store.meter().throttles()).isEqualTo(3);
    assertThat(server.seen()).hasSize(4);
  }

  @Test
  void givesUpAsRateLimitedOnceTheRetriesAreSpent() throws Exception {
    server.failNext(503, "SlowDown", 10);
    S3ObjectStore store = store(properties(1000, 2, 0), true, false);

    assertThatThrownBy(() -> store.listObjects(S3Scope.of("docs", ""), null))
        .isInstanceOf(S3AccessException.RateLimited.class)
        .hasMessageContaining("drosselt");
    assertThat(store.meter().requests()).isEqualTo(3);
  }

  @Test
  void theRunBudgetEndsTheClientInAnOrderlyWay() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 2), true, true);

    store.headObject("docs", "2025/a.pdf");
    store.headObject("docs", "2025/a.pdf");

    assertThatThrownBy(() -> store.headObject("docs", "2025/a.pdf"))
        .isInstanceOf(S3AccessException.BudgetExhausted.class)
        .hasMessageContaining("2 Anfragen");
    assertThat(server.seen()).hasSize(2);
  }

  @Test
  void mapsEveryFailureToAGermanMessageWithoutCredentials() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);
    List<Throwable> failures = new ArrayList<>();

    failures.add(catchFailure(() -> store.headObject("docs", "fehlt.pdf")));
    assertThat(failures.get(0))
        .isInstanceOf(S3AccessException.ObjectNotFound.class)
        .hasMessageContaining("docs/fehlt.pdf");

    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("gibtsnicht", ""), null)));
    assertThat(failures.get(1))
        .isInstanceOf(S3AccessException.BucketNotFound.class)
        .hasMessageContaining("gibtsnicht");

    server.failNext(403, "AccessDenied", 1);
    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("docs", ""), null)));
    assertThat(failures.get(2))
        .isInstanceOf(S3AccessException.ListForbidden.class)
        .hasMessageContaining("s3:ListBucket");

    server.failNext(403, "AccessDenied", 1);
    failures.add(catchFailure(() -> store.getObject("docs", "2025/a.pdf", 1024)));
    assertThat(failures.get(3))
        .isInstanceOf(S3AccessException.ReadForbidden.class)
        .hasMessageContaining("s3:GetObject");

    server.failNext(301, "PermanentRedirect", 1);
    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("docs", ""), null)));
    assertThat(failures.get(4))
        .isInstanceOf(S3AccessException.WrongRegionOrStyle.class)
        .hasMessageContaining("Region");

    // the SDK adjusts its clock and retries a skew answer once, so both attempts must fail
    server.failNext(403, "RequestTimeTooSkewed", 2);
    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("docs", ""), null)));
    assertThat(failures.get(5))
        .isInstanceOf(S3AccessException.ClockSkew.class)
        .hasMessageContaining("Uhr");

    server.failNext(403, "InvalidAccessKeyId", 1);
    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("docs", ""), null)));
    assertThat(failures.get(6))
        .isInstanceOf(S3AccessException.Authentication.class)
        .hasMessageContaining("Zugangsdaten");

    // a HEAD answer carries no error body, so the code is only visible on a GET
    server.failNext(403, "SignatureDoesNotMatch", 1);
    failures.add(catchFailure(() -> store.listObjects(S3Scope.of("docs", ""), null)));
    assertThat(failures.get(7)).isInstanceOf(S3AccessException.Authentication.class);

    server.failNext(403, "InvalidObjectState", 1);
    failures.add(catchFailure(() -> store.getObject("docs", "archiv/alt.pdf", 1024)));
    assertThat(failures.get(8))
        .isInstanceOf(S3AccessException.Archived.class)
        .hasMessageContaining("Archiv");

    // a 500 is retried once (retries = 1), so both attempts must fail
    server.failNext(500, "InternalError", 2);
    failures.add(catchFailure(() -> store.headObject("docs", "2025/a.pdf")));
    assertThat(failures.get(9))
        .isInstanceOf(S3AccessException.class)
        .hasMessageContaining("HTTP 500");

    server.close();
    failures.add(catchFailure(() -> store.headObject("docs", "2025/a.pdf")));
    assertThat(failures.get(10)).isInstanceOf(S3AccessException.Unreachable.class);

    for (Throwable failure : failures) {
      for (Throwable t = failure; t != null; t = t.getCause()) {
        assertThat(String.valueOf(t.getMessage()))
            .doesNotContain(SECRET_KEY)
            .doesNotContain(ACCESS_KEY)
            .doesNotContain("tok-4711")
            .doesNotContain("Signature=");
      }
      assertThat(failure.getCause()).as("no upstream cause is attached").isNull();
    }
  }

  @Test
  void testAccessReportsBucketListingAndReadingSeparately() throws Exception {
    S3ObjectStore store = store(properties(2, 1, 0), true, false);

    S3AccessCheck ok = store.testAccess(S3Scope.of("docs", "2025"));
    assertThat(ok.passed()).isTrue();
    assertThat(ok.bucketReachable()).isTrue();
    assertThat(ok.listAllowed()).isTrue();
    assertThat(ok.readAllowed()).isTrue();
    assertThat(ok.objectCount()).isEqualTo(2);
    assertThat(ok.objectCountIsLowerBound()).isTrue();

    S3AccessCheck missing = store.testAccess(S3Scope.of("gibtsnicht", ""));
    assertThat(missing.bucketReachable()).isFalse();
    assertThat(missing.failure()).isInstanceOf(S3AccessException.BucketNotFound.class);

    // HeadBucket answers 403: the bucket exists, the key may not list it
    server.failNextMatching("HEAD", "/docs", 403, "AccessDenied");
    S3AccessCheck noList = store.testAccess(S3Scope.of("docs", ""));
    assertThat(noList.bucketReachable()).isTrue();
    assertThat(noList.listAllowed()).isFalse();
    assertThat(noList.failure()).isInstanceOf(S3AccessException.ListForbidden.class);

    // HeadBucket and the listing pass, HeadObject on the first object is refused
    server.failNextMatching("HEAD", "/docs/2024/", 403, "AccessDenied");
    S3AccessCheck noRead = store.testAccess(S3Scope.of("docs", "2024"));
    assertThat(noRead.bucketReachable()).isTrue();
    assertThat(noRead.listAllowed()).isTrue();
    assertThat(noRead.readAllowed()).isFalse();
    assertThat(noRead.objectCount()).isEqualTo(1);
    assertThat(noRead.failure()).isInstanceOf(S3AccessException.ReadForbidden.class);

    S3AccessCheck empty = store.testAccess(S3Scope.of("docs", "leer/"));
    assertThat(empty.listAllowed()).isTrue();
    assertThat(empty.readAllowed()).isNull();
    assertThat(empty.objectCount()).isZero();
    assertThat(empty.passed()).isTrue();
  }

  @Test
  void listBucketsReturnsNamesOrTheNotPermittedOutcomeNeverAFailure() throws Exception {
    server.addBucket("satzungen");
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);

    assertThat(store.listBuckets())
        .isEqualTo(new S3BucketListing.Listed(List.of("docs", "satzungen")));

    server.failNext(403, "AccessDenied", 1);
    assertThat(store.listBuckets()).isInstanceOf(S3BucketListing.NotPermitted.class);
  }

  @Test
  void interruptionSurfacesAsInterruptedException() throws Exception {
    S3ObjectStore store = store(properties(1000, 1, 0), true, false);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> store.headObject("docs", "2025/a.pdf"))
          .isInstanceOf(InterruptedException.class);
    } finally {
      assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
    }
  }

  private static Throwable catchFailure(Action action) {
    try {
      action.run();
      throw new AssertionError("expected a failure");
    } catch (AssertionError e) {
      throw e;
    } catch (Throwable t) {
      return t;
    }
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }
}
