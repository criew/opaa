package io.opaa.test;

import io.opaa.indexing.chunk.VectorChunkStore;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the libraries a test class created, with everything indexed into them - the whole suite
 * shares one database, so a {@code TRUNCATE TABLE vector_store} or a {@code DELETE FROM documents}
 * without a {@code WHERE} clause would take a sibling class's still-needed rows with it.
 *
 * <p>What the library row itself carries away on {@code DELETE} (all ON DELETE CASCADE): its
 * grants, folders, metadata fields and values, keyword rows, model-extraction statistics, source
 * sync state and chat references. What it does not, and what this class therefore removes first:
 * the chunks (no foreign key at all - they are found through their {@code library_id} metadata),
 * the documents ({@code fk_documents_library_organization} is RESTRICT) and the indexing runs
 * ({@code fk_indexing_jobs_library_organization} is ON DELETE SET NULL, so a run would outlive its
 * library with a {@code NULL library_id} instead of failing).
 */
public final class OwnLibraryFixtures {

  private final JdbcTemplate jdbcTemplate;
  private final VectorChunkStore vectorChunkStore;

  OwnLibraryFixtures(JdbcTemplate jdbcTemplate, VectorChunkStore vectorChunkStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.vectorChunkStore = vectorChunkStore;
  }

  /**
   * Everything indexed into one library, leaving the library itself in place - for a class that
   * keeps its library across test methods.
   */
  public void removeContentOf(UUID libraryId) {
    vectorChunkStore.deleteByLibraryId(libraryId);
    // One statement rather than per-row deletes: PostgreSQL checks fk_documents_parent at the end
    // of the statement (NO ACTION), so a parent and its attachment go together (ADR-0022).
    jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", libraryId);
    jdbcTemplate.update(
        "DELETE FROM indexing_jobs WHERE library_id = ?", libraryId); // run events cascade
  }

  /** The libraries themselves and everything in them. */
  public void removeLibraries(UUID... libraryIds) {
    for (UUID libraryId : libraryIds) {
      removeContentOf(libraryId);
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    }
  }
}
