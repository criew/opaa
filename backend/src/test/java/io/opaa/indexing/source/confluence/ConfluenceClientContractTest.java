package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The port contract (ADR-0023), run once per deployment shape against the common {@link
 * FakeConfluenceServer}: Cloud, Data Center at the host root and Data Center under a context path.
 * Every scenario below passes for all three, or the adapter that fails it is incomplete - no
 * scenario is edition-specific.
 */
class ConfluenceClientContractTest {

  private static final Instant OLD = Instant.parse("2020-01-01T10:00:00Z");
  private static final Instant RECENT = Instant.parse("2026-09-01T12:00:00Z");
  private static final String EMAIL = "dienst@behoerde.example";
  private static final String TOKEN = "s3cr3t-token-value";

  record Deployment(ConfluenceEdition edition, String contextPath) {
    @Override
    public String toString() {
      return edition + (contextPath.isEmpty() ? "" : " under " + contextPath);
    }
  }

  static Stream<Deployment> deployments() {
    return Stream.of(
        new Deployment(ConfluenceEdition.CLOUD, ""),
        new Deployment(ConfluenceEdition.DATA_CENTER, ""),
        new Deployment(ConfluenceEdition.DATA_CENTER, "/confluence"));
  }

  private FakeConfluenceServer server;
  private HttpServer foreignHost;
  private final List<Duration> sleeps = new ArrayList<>();

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (foreignHost != null) {
      foreignHost.stop(0);
    }
  }

  private ConfluenceClient client(Deployment deployment) throws IOException {
    return client(deployment, ConfluenceProperties.defaults(), TargetAddressValidator.disabled());
  }

  private ConfluenceClient client(
      Deployment deployment, ConfluenceProperties properties, TargetAddressValidator validator)
      throws IOException {
    server = new FakeConfluenceServer(deployment.edition(), deployment.contextPath());
    seed(server);
    ConfluenceCredentials credentials =
        server.addToken(EMAIL, TOKEN, Set.of("ENG", "HR", "DOC", "OPS"));
    return factory(properties, validator).create(connection(deployment.edition(), credentials));
  }

  private ConfluenceConnection connection(
      ConfluenceEdition edition, ConfluenceCredentials credentials) {
    return new ConfluenceConnection(
        URI.create(server.baseUrl()), edition, credentials, null, -1, false);
  }

  private ConfluenceClientFactory factory(
      ConfluenceProperties properties, TargetAddressValidator validator) {
    return new ConfluenceClientFactory(properties, validator, sleeps::add);
  }

  /** Small page size so every listing paginates; three rate-limit retries, two-second cap. */
  private static ConfluenceProperties smallPages() {
    return new ConfluenceProperties(2, null, null, 3, Duration.ofSeconds(2), 0, 0, null, 0);
  }

  private static void seed(FakeConfluenceServer server) {
    server.addSpace("1", "ENG", "Engineering");
    server.addSpace("2", "HR", "Personal");
    server.addSpace("3", "SEC", "Geheimschutz");
    server.addSpace("4", "DOC", "Dokumentation");
    server.addSpace("5", "OPS", "Betrieb");
    server.addPage("100", "ENG", "Handbuch", null, "<p>Willkommen</p>", OLD);
    server.addPage("101", "ENG", "Kapitel 1", "100", "<p>Kapitel</p>", OLD);
    server.addPage("102", "ENG", "Abschnitt 1.1", "101", "<h1>Titel</h1><p>Inhalt</p>", OLD);
    server.addPage("103", "ENG", "Alt", "100", "<p>weg</p>", OLD);
    server.trashPage("103");
    server.addPage("104", "ENG", "Geheim", "100", "<p>nur für manche</p>", OLD);
    server.restrictPage("104");
    server.addPage("200", "HR", "Onboarding", null, "<p>Start</p>", OLD);
    server.addPage("300", "SEC", "Streng geheim", null, "<p>nein</p>", OLD);
    server.addAttachment(
        "9001",
        "102",
        "plan.pdf",
        "application/pdf",
        "%PDF-1.4 plan".getBytes(StandardCharsets.UTF_8));
    server.addAttachment("9002", "102", "gross.bin", "application/octet-stream", new byte[5000]);
    server.addAttachment(
        "9003", "102", "notizen.txt", "text/plain", "Notizen".getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void verifiesCredentialsAndRejectsWrongOnes(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment);
    client.verifyCredentials();

    ConfluenceCredentials wrong =
        deployment.edition() == ConfluenceEdition.CLOUD
            ? new ConfluenceCredentials.CloudApiToken(EMAIL, "falsch")
            : new ConfluenceCredentials.DataCenterPersonalAccessToken("falsch");
    ConfluenceClient wrongClient =
        factory(ConfluenceProperties.defaults(), TargetAddressValidator.disabled())
            .create(connection(deployment.edition(), wrong));

    assertThatThrownBy(wrongClient::verifyCredentials)
        .isInstanceOf(ConfluenceAccessException.Authentication.class)
        .hasMessageContaining("401")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void listsOnlyReadableSpacesAcrossAllPages(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());

    List<ConfluenceSpace> spaces = client.listSpaces();

    assertThat(spaces).extracting(ConfluenceSpace::key).containsExactly("ENG", "HR", "DOC", "OPS");
    assertThat(spaces).extracting(ConfluenceSpace::name).contains("Engineering", "Betrieb");
    assertThat(server.requests().stream().filter(r -> r.contains("space")).count())
        .as("four readable spaces at page size two need two pages")
        .isGreaterThanOrEqualTo(2);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void listsCurrentVisiblePagesWithParentsButNoBodies(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());

    List<ConfluencePageSummary> pages = client.listPages("ENG");

    assertThat(pages)
        .extracting(ConfluencePageSummary::id)
        .containsExactlyInAnyOrder("100", "101", "102");
    assertThat(pages)
        .filteredOn(p -> p.id().equals("102"))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.parentId()).isEqualTo("101");
              assertThat(p.title()).isEqualTo("Abschnitt 1.1");
              assertThat(p.version()).isEqualTo(1);
              assertThat(p.spaceKey()).isEqualTo("ENG");
            });
    assertThat(pages)
        .filteredOn(p -> p.id().equals("100"))
        .singleElement()
        .extracting(ConfluencePageSummary::parentId)
        .isNull();
    assertThat(server.requests())
        .as("a listing never asks for a body")
        .noneMatch(r -> r.contains("body-format") || r.contains("body.storage"));
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void unreadableOrUnknownSpaceIsReportedNotEmptied(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment);

    assertThatThrownBy(() -> client.listPages("SEC"))
        .isInstanceOfAny(
            ConfluenceAccessException.Forbidden.class, ConfluenceAccessException.NotFound.class)
        .hasMessageContaining("SEC")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
    assertThatThrownBy(() -> client.listPages("NOPE"))
        .isInstanceOf(ConfluenceAccessException.NotFound.class);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void fetchesPageWithBodyAncestorsVersionAndTitleFreeUrl(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment);

    ConfluencePage page = client.fetchPage("102").orElseThrow();

    assertThat(page.title()).isEqualTo("Abschnitt 1.1");
    assertThat(page.spaceKey()).isEqualTo("ENG");
    assertThat(page.storageBody()).isEqualTo("<h1>Titel</h1><p>Inhalt</p>");
    assertThat(page.ancestorTitles()).containsExactly("Handbuch", "Kapitel 1");
    assertThat(page.version()).isEqualTo(1);
    assertThat(page.status()).isEqualTo(ConfluencePageStatus.CURRENT);
    assertThat(page.lastModified()).isEqualTo(OLD);
    String expectedUrl =
        deployment.edition() == ConfluenceEdition.CLOUD
            ? server.baseUrl() + "/wiki/spaces/ENG/pages/102"
            : server.baseUrl() + "/pages/viewpage.action?pageId=102";
    assertThat(page.pageUrl()).isEqualTo(expectedUrl);
    assertThat(client.pageUrl("ENG", "102")).isEqualTo(expectedUrl);
    assertThat(page.pageUrl()).doesNotContain("Abschnitt");
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void ancestorsComeFromTheListingWhenTheSpaceWasListedBefore(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment);
    client.listPages("ENG");
    int requestsAfterListing = server.requests().size();

    ConfluencePage page = client.fetchPage("102").orElseThrow();

    assertThat(page.ancestorTitles()).containsExactly("Handbuch", "Kapitel 1");
    assertThat(server.requests().size() - requestsAfterListing)
        .as("one page fetch, no extra ancestor round trips after a listing")
        .isLessThanOrEqualTo(2);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void trashedPageIsAPositiveFindingButUnreadableOrGoneIsEmpty(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment);

    Optional<ConfluencePage> trashed = client.fetchPage("103");
    assertThat(trashed).isPresent();
    assertThat(trashed.get().status()).isEqualTo(ConfluencePageStatus.TRASHED);

    assertThat(client.fetchPage("104")).as("view-restricted page").isEmpty();
    assertThat(client.fetchPage("999")).as("never existed").isEmpty();
    assertThat(client.fetchPage("300")).as("page in an unreadable space").isEmpty();
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void listsAndDownloadsAttachmentsWithinBounds(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());

    List<ConfluenceAttachment> attachments = client.listAttachments("102");

    assertThat(attachments)
        .extracting(ConfluenceAttachment::fileName)
        .containsExactly("plan.pdf", "gross.bin", "notizen.txt");
    assertThat(server.requests().stream().filter(r -> r.contains("attachment")).count())
        .as("three attachments at page size two need two pages")
        .isGreaterThanOrEqualTo(2);
    ConfluenceAttachment plan = attachments.get(0);
    assertThat(plan.pageId()).isEqualTo("102");
    assertThat(plan.mediaType()).isEqualTo("application/pdf");
    assertThat(plan.fileSize()).isEqualTo("%PDF-1.4 plan".length());
    assertThat(plan.downloadUrl()).startsWith(server.baseUrl() + "/").contains("plan.pdf?");
    assertThat(plan.stableUrl())
        .as("identity without the volatile query")
        .startsWith(server.baseUrl() + "/")
        .endsWith("/plan.pdf")
        .doesNotContain("?");

    BoundedDownloader.DownloadedFile file = client.downloadAttachment(plan, 1024);
    try {
      assertThat(Files.readString(file.path())).isEqualTo("%PDF-1.4 plan");
      assertThat(file.contentType()).isEqualTo("application/pdf");
    } finally {
      Files.deleteIfExists(file.path());
    }

    assertThatThrownBy(() -> client.downloadAttachment(attachments.get(1), 100))
        .isInstanceOf(BoundedDownloader.AttachmentTooLargeException.class);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void refusedAttachmentDownloadNamesTheStatusNotTheNetwork(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment);
    ConfluenceAttachment plan = client.listAttachments("102").get(0);

    server.refuseAttachmentDownloads(403);
    assertThatThrownBy(() -> client.downloadAttachment(plan))
        .isInstanceOf(ConfluenceAccessException.Forbidden.class)
        .hasMessageContaining("plan.pdf")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);

    server.refuseAttachmentDownloads(404);
    assertThatThrownBy(() -> client.downloadAttachment(plan))
        .isInstanceOf(ConfluenceAccessException.NotFound.class);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void changeSearchReturnsIdentifiersOnlyAndNeverExpandsBodies(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());
    server.updatePage("101", "<p>neu</p>", RECENT);
    server.updatePage("200", "<p>neu</p>", RECENT);
    server.updatePage("100", "<p>neu</p>", RECENT);

    List<String> ids =
        client.searchPageIdsModifiedSince(Set.of("ENG", "HR"), RECENT.minusSeconds(60));

    assertThat(ids).containsExactlyInAnyOrder("100", "101", "200");
    List<String> searches = server.requests().stream().filter(r -> r.contains("search")).toList();
    assertThat(searches)
        .as("three hits at page size two need two search pages")
        .hasSizeGreaterThanOrEqualTo(2);
    assertThat(searches).noneMatch(r -> r.toLowerCase().contains("expand"));
    assertThat(searches).allMatch(r -> r.contains("lastmodified"));
    assertThat(client.searchPageIdsModifiedSince(Set.of("ENG"), RECENT.plusSeconds(3600)))
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void rateLimitSlowsDownInsteadOfFailing(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());
    server.throttleNext(2, "1");

    List<ConfluenceSpace> spaces = client.listSpaces();

    assertThat(spaces).hasSize(4);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    assertThat(client.meter().throttles()).isEqualTo(2);
    assertThat(client.meter().throttledTime()).isEqualTo(Duration.ofSeconds(2));
    assertThat(client.meter().requests()).isGreaterThan(2);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void retryAfterIsHonouredAsHttpDateAndCappedAndExhaustedRetriesFailCleanly(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());
    String inThreeSeconds =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.now().plusSeconds(3).atOffset(ZoneOffset.UTC));
    server.throttleNext(1, inThreeSeconds);
    client.verifyCredentials();
    assertThat(sleeps).hasSize(1);
    assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));

    sleeps.clear();
    server.throttleNext(1, "600");
    client.verifyCredentials();
    assertThat(sleeps).as("capped to maxRetryAfter").containsExactly(Duration.ofSeconds(2));

    sleeps.clear();
    server.throttleNext(10, "1");
    assertThatThrownBy(client::listSpaces)
        .isInstanceOf(ConfluenceAccessException.RateLimited.class)
        .hasMessageContaining("429")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
    assertThat(sleeps).hasSize(3);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void serviceUnavailableIsAFailureNotARateLimit(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment);
    server.throttleNext(1, "30", 503);

    assertThatThrownBy(client::verifyCredentials)
        .isInstanceOf(ConfluenceAccessException.class)
        .isNotInstanceOf(ConfluenceAccessException.RateLimited.class)
        .hasMessageContaining("503");
    assertThat(sleeps).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void linkToAForeignHostIsRefusedBeforeCredentialsCouldTravel(Deployment deployment)
      throws Exception {
    ConfluenceClient client = client(deployment, smallPages(), TargetAddressValidator.disabled());
    List<String> foreignRequests = new CopyOnWriteArrayList<>();
    foreignHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    foreignHost.createContext(
        "/",
        exchange -> {
          foreignRequests.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    foreignHost.start();
    // a different port is a different origin
    server.pointNextLinksAt("http://127.0.0.1:" + foreignHost.getAddress().getPort() + "/spaces");

    assertThatThrownBy(client::listSpaces)
        .isInstanceOf(ConfluenceAccessException.class)
        .hasMessageContaining("fremden Host")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
    assertThat(foreignRequests).as("the foreign host was never contacted").isEmpty();
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void aListingThatNeverEndsIsAbandonedVisibly(Deployment deployment) throws Exception {
    ConfluenceProperties threePages =
        new ConfluenceProperties(2, null, null, 3, Duration.ofSeconds(2), 0, 0, null, 3);
    ConfluenceClient client = client(deployment, threePages, TargetAddressValidator.disabled());
    // the instance keeps handing out the same first page as "next"
    String path =
        deployment.edition() == ConfluenceEdition.CLOUD
            ? "/wiki/api/v2/spaces?limit=2"
            : "/rest/api/space?limit=2&start=0";
    server.pointNextLinksAt(
        server.baseUrl().replace(deployment.contextPath(), "")
            + (deployment.edition() == ConfluenceEdition.CLOUD ? "" : deployment.contextPath())
            + path
            + "&cursor=0");

    assertThatThrownBy(client::listSpaces)
        .isInstanceOf(ConfluenceAccessException.class)
        .hasMessageContaining("Space-Liste");
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void blockedTargetNamesTheAllowlist(Deployment deployment) throws Exception {
    ConfluenceClient client =
        client(
            deployment,
            ConfluenceProperties.defaults(),
            new TargetAddressValidator(true, List.of()));

    assertThatThrownBy(client::listSpaces)
        .isInstanceOf(ConfluenceAccessException.class)
        .hasMessageContaining("gesperrten Adressbereich")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
  }

  @ParameterizedTest
  @MethodSource("deployments")
  void serverErrorNamesResourceAndStatusOnly(Deployment deployment) throws Exception {
    ConfluenceClient client = client(deployment);
    server.close();

    assertThatThrownBy(client::listSpaces)
        .isInstanceOf(ConfluenceAccessException.class)
        .hasMessageContaining("nicht erreichbar")
        .satisfies(ConfluenceClientContractTest::carriesNoCredentials);
  }

  /** Neither the token nor the e-mail may appear anywhere in the exception chain. */
  static void carriesNoCredentials(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = String.valueOf(current.getMessage());
      assertThat(message).doesNotContain(TOKEN).doesNotContain(EMAIL);
      assertThat(message).doesNotContain("Basic ").doesNotContain("Bearer ");
      current = current.getCause();
    }
  }
}
