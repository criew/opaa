package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The change cursors of {@link SourceSyncState} against the Liquibase schema: they survive a round
 * trip through {@code change_cursors}, and a connector without a change log leaves it {@code NULL}.
 */
@OpaaIntegrationTest
class SourceSyncStateRepositoryIntegrationTest {

  @Autowired private SourceSyncStateRepository repository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Sync State IT', now(), ?, ?)",
        userId,
        "sync-state-it-" + userId,
        "sync-state-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Ablage",
                null,
                userId,
                SourceTypes.S3,
                null,
                "https://ablage.example",
                null,
                null,
                false));
  }

  @AfterEach
  void tearDown() {
    if (library != null) {
      jdbcTemplate.update("DELETE FROM source_sync_state WHERE library_id = ?", library.getId());
      libraryRepository.deleteById(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void heldAndValidCursorsSurviveTheRoundTripInOrder() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.beginFullSync(UUID.randomUUID());
    state.holdPendingChangeCursors(Map.of("drive:1", "100"));
    repository.save(state);

    SourceSyncState held = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(held.pendingChangeCursors()).containsExactly(Map.entry("drive:1", "100"));
    assertThat(held.changeCursors()).isEmpty();

    held.completeFullSync(Instant.parse("2026-10-03T10:00:00Z"));
    repository.save(held);

    SourceSyncState completed = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(completed.changeCursors()).containsExactly(Map.entry("drive:1", "100"));
    assertThat(completed.pendingChangeCursors()).isEmpty();
  }

  @Test
  void aConnectorWithoutAChangeLogLeavesTheColumnNull() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.beginFullSync(UUID.randomUUID());
    state.markScopeCompleted("dokumente/2025/");
    state.completeFullSync(Instant.parse("2026-10-03T10:00:00Z"));
    repository.save(state);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT change_cursors::text FROM source_sync_state WHERE library_id = ?",
                String.class,
                library.getId()))
        .isNull();
  }
}
