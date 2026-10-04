package io.opaa.test;

import io.opaa.organization.Organization;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A bare upload library and its owner as rows only, for tests that write chunks through the
 * production path: the chunk writer refuses chunks of a library that does not exist.
 */
public final class BareLibraries {

  private BareLibraries() {}

  /** Inserts library {@code libraryId} with a fresh owner in the default organization. */
  public static void create(JdbcTemplate jdbc, UUID libraryId) {
    UUID owner = ownerOf(libraryId);
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, ?, 'test-issuer', ?, 'Bibliothek', ?)",
        owner,
        "bare-" + owner,
        "bare-" + owner + "@example.com",
        Organization.DEFAULT_ID);
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Bibliothek', 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        libraryId,
        Organization.DEFAULT_ID,
        owner);
  }

  /** Removes what {@link #create} inserted; the chunks are the caller's. */
  public static void remove(JdbcTemplate jdbc, UUID libraryId) {
    UUID owner = ownerOf(libraryId);
    jdbc.update("DELETE FROM documents WHERE library_id = ?", libraryId);
    jdbc.update("DELETE FROM assets WHERE id = ?", libraryId);
    jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", owner);
    jdbc.update("DELETE FROM users WHERE id = ?", owner);
  }

  private static UUID ownerOf(UUID libraryId) {
    return UUID.nameUUIDFromBytes(("owner-" + libraryId).getBytes());
  }
}
