package io.opaa.msgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link GraphClient} against {@link FakeGraphServer}: paging by token only, the download hop to
 * the pre-signed host without credentials and only over https, throttling under the policy's cap
 * with every attempt charged, the single retry after a refused token, and the failure kinds.
 */
class GraphClientTest {

  private static final String DRIVE = "b!drive-1";
  private static final RateLimitPolicy POLICY = RateLimitPolicy.of(3, Duration.ofSeconds(2));

  private FakeGraphServer server;
  private final List<Duration> slept = new CopyOnWriteArrayList<>();
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final SourceRequestMeter meter = new SourceRequestMeter();
  private final RateLimitListener listener =
      new RateLimitListener() {
        @Override
        public void sending() {
          events.add("sending");
        }

        @Override
        public void throttled(int statusCode, Duration wait) {
          events.add("throttled " + statusCode + " " + wait.toSeconds() + "s");
        }
      };

  @BeforeEach
  void start() {
    server = new FakeGraphServer();
    server.site("site-1", "contoso.sharepoint.com", "/sites/team", "Team");
    server.drive("site-1", DRIVE, "Dokumente", "documentLibrary");
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void readsAJsonObject() throws Exception {
    JsonNode site = client().get("sites/contoso.sharepoint.com:/sites/team", Map.of());

    assertThat(site.get("id").asString()).isEqualTo("site-1");
    assertThat(server.requests()).containsExactly("/v1.0/sites/contoso.sharepoint.com:/sites/team");
  }

  @Test
  void pagesThroughChildrenByTheSkipTokenOfTheNextLink() throws Exception {
    String root = FakeGraphServer.rootId(DRIVE);
    for (int i = 1; i <= 5; i++) {
      server.file(DRIVE, "f" + i, "Datei " + i + ".txt", root, bytes("x"));
    }
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/items/" + root + "/children";

    List<String> names = new ArrayList<>();
    String token = null;
    int pages = 0;
    do {
      GraphPage page = client.page(path, Map.of("$top", "2"), token);
      page.value().forEach(item -> names.add(item.get("name").asString()));
      assertThat(page.deltaToken()).isNull();
      token = page.nextToken();
      pages++;
    } while (token != null);

    assertThat(pages).isEqualTo(3);
    assertThat(names).hasSize(5);
    assertThat(server.requests().get(1)).endsWith("children?$top=2&$skiptoken=s2");
  }

  @Test
  void enumeratesADeltaWithTopAndReadsChangesFromItsDeltaToken() throws Exception {
    String root = FakeGraphServer.rootId(DRIVE);
    server.folder(DRIVE, "folder", "Ordner", root);
    server.file(DRIVE, "a", "a.txt", "folder", bytes("a"));
    server.file(DRIVE, "b", "b.txt", "folder", bytes("b"));
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/delta";

    GraphPage first = client.page(path, Map.of("$top", "2"), null);
    GraphPage second = client.page(path, Map.of("$top", "2"), first.nextToken());

    assertThat(first.value()).hasSize(2);
    assertThat(first.deltaToken()).isNull();
    assertThat(second.value()).hasSize(2);
    assertThat(second.nextToken()).isNull();
    assertThat(second.deltaToken()).isNotNull();
    assertThat(server.requests().get(1)).contains("token=" + first.nextToken());

    server.rename("a", "a2.txt");
    server.delete("b");
    GraphPage changes = client.page(path, Map.of(), second.deltaToken());

    assertThat(changes.value())
        .extracting(item -> item.get("id").asString())
        .containsExactlyInAnyOrder("a", "folder", root, "b");
    assertThat(changes.value())
        .filteredOn(item -> item.get("id").asString().equals("b"))
        .allSatisfy(item -> assertThat(item.has("deleted")).isTrue());
  }

  @Test
  void latestAsksForTheCurrentDeltaTokenAlone() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("a"));
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/delta";

    GraphPage latest = client.page(path, Map.of(), "latest");
    server.file(DRIVE, "b", "b.txt", FakeGraphServer.rootId(DRIVE), bytes("b"));
    GraphPage changes = client.page(path, Map.of(), latest.deltaToken());

    assertThat(latest.value()).isEmpty();
    assertThat(server.requests().getFirst()).endsWith("/root/delta?token=latest");
    assertThat(changes.value()).extracting(item -> item.get("id").asString()).contains("b");
    assertThat(changes.value()).extracting(item -> item.get("id").asString()).doesNotContain("a");
  }

  @Test
  void acceptsTheFunctionFormOfADeltaLink() throws Exception {
    readsTheDeltaThroughLinksOfTheForm(FakeGraphServer.DeltaLinkForm.FUNCTION);
  }

  @Test
  void acceptsTheUnquotedFunctionFormOfADeltaLink() throws Exception {
    readsTheDeltaThroughLinksOfTheForm(FakeGraphServer.DeltaLinkForm.FUNCTION_UNQUOTED);
  }

  private void readsTheDeltaThroughLinksOfTheForm(FakeGraphServer.DeltaLinkForm form)
      throws Exception {
    server.deltaLinkForm(form);
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("a"));
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/delta";

    GraphPage first = client.page(path, Map.of("$top", "1"), null);
    GraphPage rest = client.page(path, Map.of("$top", "1"), first.nextToken());

    assertThat(first.nextToken()).startsWith("p");
    assertThat(rest.value()).hasSize(1);
    assertThat(rest.deltaToken()).startsWith("d");
    assertThat(server.requests().get(1)).endsWith("/root/delta?$top=1&token=" + first.nextToken());
  }

  @Test
  void anExpiredDeltaTokenAsksForAResync() throws Exception {
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/delta";
    String deltaToken = client.page(path, Map.of(), null).deltaToken();
    server.expireDeltaTokens();

    assertFailure(
        () -> client.page(path, Map.of(), deltaToken),
        GraphException.Kind.RESYNC,
        410,
        "resyncRequired");
  }

  @Test
  void aPageLinkToAnotherOriginIsNotFollowed() throws Exception {
    pageLinkRewrittenTo(link -> link.replace(server.origin().toString(), "https://evil.example"));
  }

  @Test
  void aPageLinkOnAnotherPathYieldsOnlyItsTokenForTheRequestedPath() throws Exception {
    String root = FakeGraphServer.rootId(DRIVE);
    server.file(DRIVE, "a", "a.txt", root, bytes("a"));
    server.file(DRIVE, "b", "b.txt", root, bytes("b"));
    server.rewriteLinks(link -> link.replace("/root/children?", "/Items/Other?"));
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/children";

    GraphPage first = client.page(path, Map.of("$top", "1"), null);
    GraphPage second = client.page(path, Map.of("$top", "1"), first.nextToken());

    assertThat(second.value()).hasSize(1);
    assertThat(server.requests().get(1)).endsWith("/root/children?$top=1&$skiptoken=s1");
  }

  @Test
  void aPageLinkOutsideTheApiVersionIsNotFollowed() throws Exception {
    pageLinkRewrittenTo(link -> link.replace("/v1.0/", "/beta/"));
  }

  @Test
  void aTokenTakenFromAPageLinkIsEncodedWhenSentBack() throws Exception {
    String root = FakeGraphServer.rootId(DRIVE);
    server.file(DRIVE, "a", "a.txt", root, bytes("a"));
    server.file(DRIVE, "b", "b.txt", root, bytes("b"));
    server.rewriteLinks(link -> link.replace("$skiptoken=s1", "$skiptoken=s1%26%24top%3D99"));
    GraphClient client = client();
    String path = "drives/" + DRIVE + "/root/children";
    String token = client.page(path, Map.of("$top", "1"), null).nextToken();

    org.assertj.core.api.Assertions.catchThrowable(
        () -> client.page(path, Map.of("$top", "1"), token));

    assertThat(token).isEqualTo("s1&$top=99");
    assertThat(server.requests().get(1)).endsWith("?$top=1&$skiptoken=s1%26%24top%3D99");
  }

  @Test
  void aPageLinkWithoutItsTokenIsNotFollowed() throws Exception {
    pageLinkRewrittenTo(link -> link.replace("$skiptoken=", "$other="));
  }

  private void pageLinkRewrittenTo(java.util.function.UnaryOperator<String> rewrite)
      throws Exception {
    String root = FakeGraphServer.rootId(DRIVE);
    server.file(DRIVE, "a", "a.txt", root, bytes("a"));
    server.file(DRIVE, "b", "b.txt", root, bytes("b"));
    server.rewriteLinks(rewrite);
    GraphClient client = client();

    assertFailure(
        () -> client.page("drives/" + DRIVE + "/root/children", Map.of("$top", "1"), null),
        GraphException.Kind.BLOCKED,
        200,
        null);
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void downloadsFromThePresignedHostWithoutAuthorization() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("Inhalt"));

    Path file = client().download("drives/" + DRIVE + "/items/a/content", 1024);
    try {
      assertThat(Files.readString(file)).isEqualTo("Inhalt");
    } finally {
      Files.deleteIfExists(file);
    }
    assertThat(server.downloadAuthorizations()).containsExactly("(none)");
    assertThat(meter.bytesDownloaded()).isEqualTo(6);
    assertThat(events).containsExactly("sending");
  }

  @Test
  void aDownloadRedirectToPlainHttpIsRefusedBeforeItIsSent() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("Inhalt"));
    server.downloadHost(FakeGraphServer.DownloadHost.PLAIN_HTTP);

    GraphException failure =
        assertFailure(
            () -> client().download("drives/" + DRIVE + "/items/a/content", 1024),
            GraphException.Kind.BLOCKED,
            0,
            null);

    assertThat(server.plainDownloadHits()).isZero();
    assertThat(failure.getMessage()).doesNotContain(server.presignedSecret(), "/blob/");
  }

  @Test
  void theDownloadTargetIsValidatedOnItsOwnHop() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("Inhalt"));
    server.downloadHost(FakeGraphServer.DownloadHost.HTTPS_BY_NAME);
    GraphClient client =
        client(
            () -> FakeGraphServer.TOKEN,
            null,
            new TargetAddressValidator(true, List.of("127.0.0.1")),
            POLICY,
            Duration.ofSeconds(5),
            GraphClient.DEFAULT_MAX_JSON_BYTES);

    GraphException failure =
        assertFailure(
            () -> client.download("drives/" + DRIVE + "/items/a/content", 1024),
            GraphException.Kind.BLOCKED,
            0,
            null);

    assertThat(server.downloadAuthorizations()).isEmpty();
    assertThat(failure.getMessage()).doesNotContain(server.presignedSecret(), "/blob/");
  }

  @Test
  void aDownloadOverTheBoundIsTooLargeAndLeavesNoTempFile() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("x".repeat(100)));
    List<Path> before = tempFiles();

    assertFailure(
        () -> client().download("drives/" + DRIVE + "/items/a/content", 10),
        GraphException.Kind.TOO_LARGE,
        200,
        null);
    assertThat(tempFiles()).containsExactlyInAnyOrderElementsOf(before);
  }

  private static List<Path> tempFiles() throws java.io.IOException {
    try (var files =
        Files.list(Path.of(System.getProperty("java.io.tmpdir")))
            .filter(file -> file.getFileName().toString().startsWith("opaa-msgraph-"))) {
      return files.toList();
    }
  }

  @Test
  void aRefusalByThePresignedHostIsTransientAndRenewsNoToken() throws Exception {
    server.file(DRIVE, "a", "a.txt", FakeGraphServer.rootId(DRIVE), bytes("Inhalt"));
    server.failNext("/blob/", 401, "expired", null, 1);
    AtomicInteger renewals = new AtomicInteger();
    GraphClient client =
        client(
            () -> FakeGraphServer.TOKEN,
            sent -> renewals.incrementAndGet() > 0,
            TargetAddressValidator.disabled(),
            POLICY,
            Duration.ofSeconds(5),
            GraphClient.DEFAULT_MAX_JSON_BYTES);

    GraphException failure =
        assertFailure(
            () -> client.download("drives/" + DRIVE + "/items/a/content", 1024),
            GraphException.Kind.TRANSIENT,
            401,
            null);

    assertThat(renewals).hasValue(0);
    assertThat(failure.getMessage()).doesNotContain(server.presignedSecret());
  }

  @Test
  void aThrottleIsWaitedOutCappedAtTheMaximumAndEveryAttemptIsCharged() throws Exception {
    server.failNext("/root/delta", 429, "activityLimitReached", "3600", 1);

    client().page("drives/" + DRIVE + "/root/delta", Map.of(), null);

    assertThat(slept).containsExactly(Duration.ofSeconds(2));
    assertThat(events).containsExactly("sending", "throttled 429 2s", "sending");
  }

  @Test
  void anUnavailableServiceIsWaitedOutByItsRetryAfter() throws Exception {
    server.failNext("/root/delta", 503, "serviceNotAvailable", "1", 1);

    client().page("drives/" + DRIVE + "/root/delta", Map.of(), null);

    assertThat(slept).containsExactly(Duration.ofSeconds(1));
    assertThat(events).containsExactly("sending", "throttled 503 1s", "sending");
  }

  @Test
  void throttlingBeyondTheRetriesIsTransient() {
    server.failNext("/root/delta", 429, "activityLimitReached", "1", 10);

    assertFailure(
        () -> client().page("drives/" + DRIVE + "/root/delta", Map.of(), null),
        GraphException.Kind.TRANSIENT,
        429,
        "activityLimitReached");
    assertThat(events.stream().filter("sending"::equals)).hasSize(4);
    assertThat(slept).hasSize(3);
  }

  @Test
  void aBudgetRefusingAnAttemptEndsTheCall() {
    server.failNext("/root/delta", 429, "activityLimitReached", "1", 10);
    RateLimitListener budget =
        new RateLimitListener() {
          private int attempts;

          @Override
          public void sending() {
            if (++attempts > 2) {
              throw new IllegalStateException("budget spent");
            }
          }
        };
    GraphClient client =
        new GraphClient(
            server.origin(),
            () -> FakeGraphServer.TOKEN,
            null,
            server.httpClient(),
            TargetAddressValidator.disabled(),
            new RateLimitHandling(POLICY, slept::add, budget),
            meter,
            Duration.ofSeconds(5),
            GraphClient.DEFAULT_MAX_JSON_BYTES);

    assertThatThrownBy(() -> client.page("drives/" + DRIVE + "/root/delta", Map.of(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("budget spent");
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void aRefusedTokenIsRetriedOnceWithTheRenewedOne() throws Exception {
    server.acceptOnly("renewed");
    List<String> tokens = new ArrayList<>(List.of(FakeGraphServer.TOKEN, "renewed"));
    List<String> rejected = new ArrayList<>();
    GraphClient client =
        client(
            () -> tokens.size() > 1 ? tokens.removeFirst() : tokens.getFirst(),
            sent -> rejected.add(sent),
            TargetAddressValidator.disabled(),
            POLICY,
            Duration.ofSeconds(5),
            GraphClient.DEFAULT_MAX_JSON_BYTES);

    client.get("drives/" + DRIVE, Map.of());

    assertThat(rejected).containsExactly(FakeGraphServer.TOKEN);
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void aTokenRefusedAgainAfterTheRenewalIsUnauthorizedAfterExactlyOneRetry() {
    server.acceptOnly("never");
    AtomicInteger renewals = new AtomicInteger();
    GraphClient client =
        client(
            () -> FakeGraphServer.TOKEN,
            sent -> renewals.incrementAndGet() > 0,
            TargetAddressValidator.disabled(),
            POLICY,
            Duration.ofSeconds(5),
            GraphClient.DEFAULT_MAX_JSON_BYTES);

    assertFailure(
        () -> client.get("drives/" + DRIVE, Map.of()),
        GraphException.Kind.UNAUTHORIZED,
        401,
        "InvalidAuthenticationToken");
    assertThat(renewals).hasValue(1);
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void failuresAreMappedToTheirKindWithGraphsErrorCode() {
    server.failNext("drives/forbidden", 403, "accessDenied", null, 1);
    server.failNext("drives/broken", 500, "generalException", null, 1);
    GraphClient client = client();

    assertFailure(
        () -> client.get("drives/forbidden", Map.of()),
        GraphException.Kind.FORBIDDEN,
        403,
        "accessDenied");
    assertFailure(
        () -> client.get("drives/missing", Map.of()),
        GraphException.Kind.NOT_FOUND,
        404,
        "itemNotFound");
    assertFailure(
        () -> client.get("drives/broken", Map.of()),
        GraphException.Kind.TRANSIENT,
        500,
        "generalException");
  }

  @Test
  void anAnswerTricklingInPastTheTimeoutIsCutOff() {
    server.trickle("sites/site-1");
    GraphClient client =
        client(
            () -> FakeGraphServer.TOKEN,
            null,
            TargetAddressValidator.disabled(),
            POLICY,
            Duration.ofSeconds(1),
            GraphClient.DEFAULT_MAX_JSON_BYTES);
    long start = System.nanoTime();

    assertFailure(
        () -> client.get("sites/site-1", Map.of()), GraphException.Kind.TRANSIENT, 200, null);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void anAnswerOverTheJsonBoundIsRefused() {
    GraphClient client =
        client(
            () -> FakeGraphServer.TOKEN,
            null,
            TargetAddressValidator.disabled(),
            POLICY,
            Duration.ofSeconds(5),
            16);

    assertFailure(
        () -> client.get("sites/site-1", Map.of()), GraphException.Kind.TRANSIENT, 200, null);
  }

  private GraphClient client() {
    return client(
        () -> FakeGraphServer.TOKEN,
        null,
        TargetAddressValidator.disabled(),
        POLICY,
        Duration.ofSeconds(5),
        GraphClient.DEFAULT_MAX_JSON_BYTES);
  }

  private GraphClient client(
      Supplier<String> token,
      Predicate<String> renewed,
      TargetAddressValidator validator,
      RateLimitPolicy policy,
      Duration timeout,
      long maxJsonBytes) {
    return new GraphClient(
        server.origin(),
        token,
        renewed,
        server.httpClient(),
        validator,
        new RateLimitHandling(policy, slept::add, listener),
        meter,
        timeout,
        maxJsonBytes);
  }

  private static GraphException assertFailure(
      ThrowingCallable call, GraphException.Kind kind, int status, String errorCode) {
    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
    assertThat(thrown).isInstanceOf(GraphException.class);
    GraphException failure = (GraphException) thrown;
    assertThat(failure.kind()).as(failure.getMessage()).isEqualTo(kind);
    assertThat(failure.status()).isEqualTo(status);
    assertThat(failure.errorCode()).isEqualTo(errorCode);
    assertThat(failure.getMessage()).doesNotContain(FakeGraphServer.TOKEN);
    return failure;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
