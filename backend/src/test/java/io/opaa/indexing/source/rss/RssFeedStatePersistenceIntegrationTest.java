package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The feed server's {@code ETag} and {@code Last-Modified} are stored as sent, whatever their
 * length, against the Liquibase schema: the server alone decides their form.
 */
@OpaaIntegrationTest
class RssFeedStatePersistenceIntegrationTest {

  private static final String FEED_URL = "https://rathaus.example/aktuelles.rss";

  @Autowired private RssFeedStateRepository feedStateRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Feed State IT User', now(), ?,"
            + " ?)",
        userId,
        "feed-state-it-" + userId,
        "feed-state-it-" + userId + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Feedzustand", null, userId));
  }

  @AfterEach
  void removeOwnRows() {
    if (library != null) {
      jdbcTemplate.update("DELETE FROM rss_feed_state WHERE library_id = ?", library.getId());
      ownLibraryFixtures.removeLibraries(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void headersBeyondTheFormerColumnWidthsAreStoredAsSent() {
    String etag = "W/\"" + "feed-revision-0123456789abcdef-".repeat(20) + "\"";
    String lastModified = "Sat, 11 Oct 2026 08:00:00 GMT; " + "origin=archiv-cache ".repeat(12);
    assertThat(etag.length()).isGreaterThan(500);
    assertThat(lastModified.length()).isGreaterThan(200);
    FeedFetcher fetcher = new FeedFetcher(null, feedStateRepository, null, null, null);

    fetcher.saveState(library.getId(), FEED_URL, responseWith(etag, lastModified));

    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT etag, last_modified FROM rss_feed_state WHERE library_id = ?",
                library.getId()))
        .containsEntry("etag", etag)
        .containsEntry("last_modified", lastModified);
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<InputStream> responseWith(String etag, String lastModified) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("ETag", List.of(etag), "Last-Modified", List.of(lastModified)),
                (name, value) -> true));
    return response;
  }
}
