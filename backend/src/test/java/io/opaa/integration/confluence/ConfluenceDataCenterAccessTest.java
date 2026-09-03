package io.opaa.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceAccessException;
import io.opaa.indexing.source.confluence.ConfluenceAttachment;
import io.opaa.indexing.source.confluence.ConfluenceClient;
import io.opaa.indexing.source.confluence.ConfluenceClientFactory;
import io.opaa.indexing.source.confluence.ConfluenceConnection;
import io.opaa.indexing.source.confluence.ConfluenceCredentials;
import io.opaa.indexing.source.confluence.ConfluenceEditionDetector;
import io.opaa.indexing.source.confluence.ConfluencePage;
import io.opaa.indexing.source.confluence.ConfluencePageStatus;
import io.opaa.indexing.source.confluence.ConfluencePageSummary;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The access scenarios of #1171 against the real Data Center: edition detection, credential
 * verification, space listing with pagination, single page and attachment fetches, and visible
 * skipping where the token may not read. Runs only via {@code ./gradlew confluenceIntegrationTest}
 * and only with Docker available; {@code OPAA_CONFLUENCE_IT=true} is the explicit switch so a
 * developer's plain {@code test} run never starts a Confluence by accident.
 */
@EnabledIfEnvironmentVariable(named = "OPAA_CONFLUENCE_IT", matches = "true")
class ConfluenceDataCenterAccessTest {

  private static ConfluenceDataCenterFixture confluence;
  private static ConfluenceClientFactory factory;

  @BeforeAll
  static void start() {
    confluence = ConfluenceDataCenterFixture.get();
    // the container answers on loopback, which the default target validation rejects by design
    factory =
        new ConfluenceClientFactory(
            new ConfluenceProperties(2, null, null, 0, null, 0, 0, null, 0, null, null),
            TargetAddressValidator.disabled());
  }

  private static ConfluenceClient client(String token) throws ConfluenceAccessException {
    return factory.create(
        new ConfluenceConnection(
            URI.create(confluence.baseUrl()),
            ConfluenceEdition.DATA_CENTER,
            new ConfluenceCredentials.DataCenterPersonalAccessToken(token),
            null,
            -1,
            false));
  }

  @Test
  void detectsDataCenterWithoutCredentialsAndVerifiesTheToken() throws Exception {
    ConfluenceEditionDetector.Detected detected =
        factory.editionDetector().detect(confluence.baseUrl() + "/", null, -1, false);

    assertThat(detected.edition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(detected.baseUrl().toString()).isEqualTo(confluence.baseUrl());
    assertThat(
            factory
                .editionDetector()
                .confirms(confluence.baseUrl(), null, -1, false, ConfluenceEdition.DATA_CENTER))
        .isTrue();
    assertThat(
            factory
                .editionDetector()
                .confirms(confluence.baseUrl(), null, -1, false, ConfluenceEdition.CLOUD))
        .isFalse();

    client(confluence.adminToken()).verifyCredentials();
    // pinned to the mechanism, not just the class: Data Center serves an unknown token anonymously
    // (HTTP 200) - should Atlassian ever switch to 401, this line is what tells us
    assertThatThrownBy(() -> client("kein-gueltiges-token").verifyCredentials())
        .isInstanceOf(ConfluenceAccessException.Authentication.class)
        .hasMessageContaining("anonym")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("kein-gueltiges-token"));
  }

  @Test
  void listsSpacesAcrossPagesAndOnlyThoseTheTokenMayRead() throws Exception {
    List<ConfluenceSpace> asAdmin = client(confluence.adminToken()).listSpaces();
    assertThat(asAdmin).extracting(ConfluenceSpace::key).contains("ENG", "HR", "SEC");
    assertThat(asAdmin)
        .filteredOn(s -> s.key().equals("ENG"))
        .singleElement()
        .extracting(ConfluenceSpace::name)
        .isEqualTo("Engineering");

    List<ConfluenceSpace> asLimited = client(confluence.limitedToken()).listSpaces();
    assertThat(asLimited)
        .extracting(ConfluenceSpace::key)
        .contains("ENG", "HR")
        .doesNotContain("SEC");
  }

  @Test
  void listsThePageHierarchyWithoutRestrictedAndTrashedPages() throws Exception {
    ConfluenceClient limited = client(confluence.limitedToken());

    // a real space always carries the home page Confluence creates with it ("Engineering Home")
    List<ConfluencePageSummary> eng = limited.listPages("ENG");
    assertThat(eng)
        .extracting(ConfluencePageSummary::title)
        .contains("Engineering Home", "Handbuch", "Kapitel 1", "Abschnitt 1.1")
        .doesNotContain("Nur Admin");
    assertThat(eng)
        .filteredOn(p -> p.title().equals("Abschnitt 1.1"))
        .singleElement()
        .extracting(ConfluencePageSummary::parentId)
        .isEqualTo(confluence.pageId("Kapitel 1"));

    List<ConfluencePageSummary> hr = limited.listPages("HR");
    assertThat(hr)
        .extracting(ConfluencePageSummary::title)
        .contains("Onboarding")
        .doesNotContain("Alt");

    assertThatThrownBy(() -> limited.listPages("SEC"))
        .isInstanceOfAny(
            ConfluenceAccessException.Forbidden.class, ConfluenceAccessException.NotFound.class);
  }

  @Test
  void fetchesAPageWithBodyAncestorsVersionAndUrlAndItsAttachments() throws Exception {
    ConfluenceClient limited = client(confluence.limitedToken());
    String id = confluence.pageId("Abschnitt 1.1");

    ConfluencePage page = limited.fetchPage(id).orElseThrow();
    assertThat(page.title()).isEqualTo("Abschnitt 1.1");
    assertThat(page.spaceKey()).isEqualTo("ENG");
    assertThat(page.status()).isEqualTo(ConfluencePageStatus.CURRENT);
    assertThat(page.version()).isEqualTo(1);
    assertThat(page.ancestorTitles()).containsExactly("Handbuch", "Kapitel 1");
    assertThat(page.storageBody()).contains("Zuständigkeiten").contains("<table");
    assertThat(page.pageUrl())
        .isEqualTo(confluence.baseUrl() + "/pages/viewpage.action?pageId=" + id);
    assertThat(page.lastModified()).isNotNull().isBefore(Instant.now().plusSeconds(60));

    List<ConfluenceAttachment> attachments = limited.listAttachments(id);
    assertThat(attachments)
        .extracting(ConfluenceAttachment::fileName)
        .containsExactlyInAnyOrder("plan.pdf", "notizen.txt");
    ConfluenceAttachment notes =
        attachments.stream()
            .filter(a -> a.fileName().equals("notizen.txt"))
            .findFirst()
            .orElseThrow();
    assertThat(notes.mediaType()).startsWith("text/plain");
    assertThat(notes.stableUrl()).doesNotContain("?");
    BoundedDownloader.DownloadedFile file = limited.downloadAttachment(notes);
    try {
      assertThat(Files.readString(file.path())).isEqualTo("Notizen zur Sitzung");
    } finally {
      Files.deleteIfExists(file.path());
    }
  }

  @Test
  void skipsWhatTheTokenMayNotReadVisiblyAndReportsTheTrashAsAPositiveFinding() throws Exception {
    ConfluenceClient limited = client(confluence.limitedToken());
    ConfluenceClient admin = client(confluence.adminToken());

    // a read-restricted page is invisible to the limited token - no listing, 404 on fetch - and
    // fully readable for the admin
    assertThat(limited.fetchPage(confluence.pageId("Nur Admin"))).isEmpty();
    assertThat(admin.fetchPage(confluence.pageId("Nur Admin"))).isPresent();

    // a page in a space the token may not see
    assertThat(limited.fetchPage(confluence.pageId("Streng geheim"))).isEmpty();

    // the trash is a positive finding (ADR-0023, Entscheidung 4), for both tokens
    Optional<ConfluencePage> trashed = admin.fetchPage(confluence.trashedPageId());
    assertThat(trashed).isPresent();
    assertThat(trashed.get().status()).isEqualTo(ConfluencePageStatus.TRASHED);
    assertThat(limited.fetchPage(confluence.trashedPageId()))
        .isPresent()
        .get()
        .extracting(ConfluencePage::status)
        .isEqualTo(ConfluencePageStatus.TRASHED);
  }

  @Test
  void changeSearchListsIdentifiersOfRecentlyModifiedPagesOnly() throws Exception {
    ConfluenceClient admin = client(confluence.adminToken());
    Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");

    // CQL reads the search index, which Data Center updates asynchronously after a write - the
    // fixture's youngest pages can lag behind by a few seconds, so the search is repeated until the
    // index has caught up (the first CI runs saw "Onboarding" missing once).
    List<String> ids = admin.searchPageIdsModifiedSince(Set.of("ENG", "HR"), longAgo);
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
    while (!ids.containsAll(List.of(confluence.pageId("Handbuch"), confluence.pageId("Onboarding")))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(3000);
      ids = admin.searchPageIdsModifiedSince(Set.of("ENG", "HR"), longAgo);
    }

    assertThat(ids)
        .contains(confluence.pageId("Handbuch"), confluence.pageId("Onboarding"))
        .doesNotContain(confluence.trashedPageId(), confluence.pageId("Streng geheim"));
    // a full day ahead: CQL evaluates lastmodified in the instance's time zone, not UTC
    assertThat(
            admin.searchPageIdsModifiedSince(Set.of("ENG"), Instant.now().plus(Duration.ofDays(1))))
        .isEmpty();
  }
}
