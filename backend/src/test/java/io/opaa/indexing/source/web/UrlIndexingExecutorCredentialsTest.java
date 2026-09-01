package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #483 acceptance criterion: a run reads its credentials from the {@link KnowledgeLibrary} entity
 * exactly as it did before this issue, even though the underlying database column is now encrypted
 * ({@code SourceCredentialsConverter}). Runs against a real Postgres/Liquibase schema (not
 * Hibernate-generated DDL) so {@code source_credentials} is really the encrypted, widened {@code
 * varchar(3000)} column from migration 029, and reloads the entity through {@link
 * KnowledgeLibraryRepository} - the same path {@code SourceIndexingRunService} uses before calling
 * {@link UrlIndexingExecutor#execute} - rather than asserting against the in-memory object this
 * test itself constructed.
 *
 * <p>Carries the canonical {@link io.opaa.test.OpaaIntegrationTest} signature (AGENTS.md, "Spring-
 * Testkontexte"): this class never uses MockMvc or any web layer, so it shares one cached context
 * and one container with every other class on that same meta-annotation instead of building its
 * own.
 */
@OpaaIntegrationTest
class UrlIndexingExecutorCredentialsTest {

  private static final String OWNER_EMAIL = "url-indexing-credentials-it@example.com";

  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = ?)",
        OWNER_EMAIL);
    jdbcTemplate.update("DELETE FROM users WHERE email = ?", OWNER_EMAIL);
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'URL Indexing Credentials IT"
            + " User', now(), ?, ?)",
        userId,
        "url-indexing-credentials-it-" + userId,
        OWNER_EMAIL,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = ?)",
        OWNER_EMAIL);
    jdbcTemplate.update("DELETE FROM users WHERE email = ?", OWNER_EMAIL);
  }

  @Test
  void aRunReadsDecryptedCredentialsFromALibraryEntityReloadedFromTheDatabase() {
    KnowledgeLibrary saved =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Web-Verzeichnis mit Zugangsdaten",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.HTTP_DIRECTORY,
                null,
                "https://files.example.com/documents/",
                "proxy.example.com:8080",
                "admin:super-secret-password",
                true));

    // The raw column is encrypted, not the plaintext just passed above - confirms this test
    // actually exercises SourceCredentialsConverter, not an unencrypted test schema.
    String rawColumnValue =
        jdbcTemplate.queryForObject(
            "SELECT source_credentials FROM knowledge_libraries WHERE id = ?",
            String.class,
            saved.getId());
    assertThat(rawColumnValue).doesNotContain("admin:super-secret-password");

    KnowledgeLibrary reloaded = libraryRepository.findById(saved.getId()).orElseThrow();

    UrlIndexingRequest request = UrlIndexingExecutor.toUrlIndexingRequest(reloaded);

    assertThat(request.url()).isEqualTo("https://files.example.com/documents/");
    assertThat(request.proxy()).isEqualTo("proxy.example.com:8080");
    assertThat(request.credentials()).isEqualTo("admin:super-secret-password");
    assertThat(request.insecureSsl()).isTrue();
  }
}
