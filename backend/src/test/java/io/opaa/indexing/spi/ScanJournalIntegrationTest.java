package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.RunAbandonedException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ScanJournal} against the Flyway schema: a round's progress and the presence written with
 * it are committed together or not at all, the end of a round drops its presence, and only a run
 * whose job is still running writes at all.
 */
@OpaaIntegrationTest
class ScanJournalIntegrationTest {

  @Autowired private ScanJournal journal;
  @Autowired private SourceSyncStateRepository repository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository jobRepository;

  private UUID userId;
  private KnowledgeLibrary library;
  private UUID jobId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Scan Journal IT', now(), ?, ?)",
        userId,
        "scan-journal-it-" + userId,
        "scan-journal-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Freigabe",
                null,
                userId,
                SourceTypes.S3,
                null,
                "https://ablage.example",
                null));
    Document document = new Document("a.txt", "a.txt", "text/plain", 1L, SourceTypes.S3);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.applySourceContext(new SourceDocumentContext("Projekte", null));
    documentRepository.save(document);
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(library.getId());
    job.setOrganizationId(Organization.DEFAULT_ID);
    jobId = jobRepository.saveAndFlush(job).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE id = ?", jobId);
    jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", library.getId());
    jdbcTemplate.update("DELETE FROM source_sync_state WHERE library_id = ?", library.getId());
    libraryRepository.deleteById(library.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void theProgressAndThePresenceOfASaveAreKeptTogetherOrNotAtAll() {
    UUID scan = UUID.randomUUID();
    SourceSyncState state = journal.save(jobId, new SourceSyncState(library.getId()));
    state.recordScanProgress(progress(scan, "erste"));
    state = journal.save(jobId, state, scan, library.getId(), List.of("a.txt"));
    assertThat(journal.presentPaths(state.getId(), scan)).containsExactly("a.txt");

    SourceSyncState failing = state;
    failing.recordScanProgress(progress(scan, "zweite"));
    // a presence row without a round cannot be written: the progress beside it is not either
    assertThatThrownBy(() -> journal.save(jobId, failing, null, library.getId(), List.of("a.txt")))
        .isInstanceOf(DataAccessException.class);

    SourceSyncState stored = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(stored.scanProgress().containers().get("Projekte").checkpoint()).isEqualTo("erste");
  }

  @Test
  void theEndOfARoundDropsItsPresence() {
    UUID scan = UUID.randomUUID();
    SourceSyncState state = journal.save(jobId, new SourceSyncState(library.getId()));
    state.recordScanProgress(progress(scan, "erste"));
    state = journal.save(jobId, state, scan, library.getId(), List.of("a.txt"));

    state.completeFullSync(Instant.parse("2026-10-04T12:00:00Z"));
    state = journal.saveEnded(jobId, state);

    assertThat(journal.presentPaths(state.getId(), scan)).isEmpty();
    assertThat(repository.findByLibraryId(library.getId()).orElseThrow().scanProgress()).isNull();
  }

  private static SourceSyncState.ScanProgress progress(UUID scan, String checkpoint) {
    return new SourceSyncState.ScanProgress(
        scan,
        Instant.parse("2026-10-04T10:00:00Z"),
        "v2|1024|txt",
        null,
        Map.of("Projekte", new SourceSyncState.ContainerProgress(checkpoint, 0, 1, true, null)),
        null,
        null,
        null,
        "LOCATION_IDENTITY");
  }

  // regression guard for #2202: a run never writes back a state deleted while it held it
  @Test
  void aStateDeletedWhileARunHeldItIsNotWrittenAgain() {
    SourceSyncState saved = journal.save(jobId, new SourceSyncState(library.getId()));
    SourceSyncState held = repository.findByLibraryId(library.getId()).orElseThrow();
    jdbcTemplate.update("DELETE FROM source_sync_state WHERE id = ?", saved.getId());
    held.recordScanProgress(progress(UUID.randomUUID(), "alt"));

    assertThatThrownBy(() -> journal.save(jobId, held))
        .isInstanceOf(IndexingRunFailedException.class)
        .hasMessage(ScanJournal.DISCARDED_MESSAGE);
    assertThat(repository.findByLibraryId(library.getId())).isEmpty();
  }

  /**
   * A run the recovery gave up - or that ended elsewhere - no longer writes the state, which by
   * then belongs to the run after it; the presence beside it is not written either.
   */
  @Test
  void aRunWhoseJobIsNoLongerRunningWritesNoState() {
    UUID scan = UUID.randomUUID();
    SourceSyncState state = journal.save(jobId, new SourceSyncState(library.getId()));
    state.recordScanProgress(progress(scan, "erste"));
    state = journal.save(jobId, state, scan, library.getId(), List.of("a.txt"));
    jdbcTemplate.update("UPDATE indexing_jobs SET status = 'FAILED' WHERE id = ?", jobId);

    SourceSyncState zombie = state;
    zombie.recordScanProgress(progress(scan, "zombie"));
    assertThatThrownBy(() -> journal.save(jobId, zombie)).isInstanceOf(RunAbandonedException.class);
    assertThatThrownBy(() -> journal.saveEnded(jobId, zombie))
        .isInstanceOf(RunAbandonedException.class);
    assertThatThrownBy(
            () ->
                journal.recordPresence(
                    jobId, zombie.getId(), UUID.randomUUID(), library.getId(), List.of("a.txt")))
        .isInstanceOf(RunAbandonedException.class);

    SourceSyncState stored = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(stored.scanProgress().containers().get("Projekte").checkpoint()).isEqualTo("erste");
    assertThat(journal.presentPaths(stored.getId(), scan)).containsExactly("a.txt");
  }
}
