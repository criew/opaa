package io.opaa.test;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plain rows for self-disclosure exporter tests: a throwaway organization, accounts, a space and
 * libraries, inserted directly so that each exporter is tested against exactly the rows it reads.
 * The caller removes them, scoped to its organization.
 */
public final class SelfDisclosureTestData {

  private final JdbcTemplate jdbc;
  private final UUID organizationId;

  private SelfDisclosureTestData(JdbcTemplate jdbc, UUID organizationId) {
    this.jdbc = jdbc;
    this.organizationId = organizationId;
  }

  /** A new organization named {@code name}. */
  public static SelfDisclosureTestData newOrganization(JdbcTemplate jdbc, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO organizations (id, name, created_at) VALUES (?, ?, now())", id, name);
    return new SelfDisclosureTestData(jdbc, id);
  }

  public UUID organizationId() {
    return organizationId;
  }

  /** An account whose subject, address and display name all contain {@code name}. */
  public UUID user(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'sd-test-issuer', ?, ?, now(), 'USER', ?)",
        id,
        "subject-" + name,
        name + "@example.com",
        name,
        organizationId);
    return id;
  }

  /** A space named {@code name} owned by {@code owner}. */
  public UUID space(String name, UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO spaces (id, name, is_default, owner_id, organization_id, created_at,"
            + " updated_at) VALUES (?, ?, false, ?, ?, now(), now())",
        id,
        name,
        owner,
        organizationId);
    return id;
  }

  /**
   * A knowledge library named {@code name} owned by {@code owner}; {@code ownerOnly} makes it a
   * private library. {@code sourceType} {@code UPLOAD} carries no source.
   */
  public UUID library(String name, UUID owner, boolean ownerOnly, String sourceType) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " owner_only) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, ?)",
        id,
        organizationId,
        name,
        owner,
        ownerOnly);
    jdbc.update(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES (?, ?, ?)",
        id,
        organizationId,
        sourceType);
    return id;
  }

  /**
   * A document in {@code library}, uploaded by {@code uploadedBy} (may be {@code null}), with the
   * columns the directory must leave out set to {@code marker}-carrying values.
   */
  public UUID document(
      UUID library,
      String fileName,
      String filePath,
      String folder,
      String sourceType,
      UUID uploadedBy,
      String marker) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " uploaded_by_user_id, source_hierarchy_path, source_container_key,"
            + " source_entry_url, context_prefix_title, error_message, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, 1, now(), ?, 'INDEXED', ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, now())",
        id,
        fileName,
        filePath,
        marker + "-checksum",
        sourceType,
        library,
        organizationId,
        uploadedBy,
        folder,
        marker + "-container-key",
        "https://source.example/" + marker + "-entry",
        marker + "-prefix-title",
        marker + "-error");
    return id;
  }

  /** Removes every row of the organization the exporter tests write, and the organization. */
  public void removeAll(OwnOrganizationFixtures organizations) {
    jdbc.update("DELETE FROM documents WHERE organization_id = ?", organizationId);
    organizations.removeOrganizations(organizationId);
  }
}
