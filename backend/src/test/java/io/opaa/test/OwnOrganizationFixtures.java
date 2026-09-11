package io.opaa.test;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the throwaway organizations a test class created for itself, together with every identity
 * and permission row inside them - the whole suite shares one database, so a blanket {@code
 * deleteAll()} over {@code users}, {@code spaces} or {@code knowledge_libraries} would take a
 * sibling class's still-needed rows with it.
 *
 * <p>Covers exactly the tables a class working through the space, chat and asset-association
 * services fills; {@code chat_messages} and {@code chat_library_references} come along with their
 * chat (ON DELETE CASCADE). Deliberately <b>not</b> documents, indexing jobs, groups or the
 * metadata tables: those reference their library or organization with RESTRICT, so a class that
 * creates them fails loudly here instead of being cleaned up halfway.
 */
public final class OwnOrganizationFixtures {

  /**
   * Ordered so that every child goes before its parent; each statement is scoped by {@code
   * organization_id}, which every one of these tables carries.
   */
  private static final List<String> TABLES_IN_DELETION_ORDER =
      List.of(
          "chats",
          "space_asset_associations",
          "notifications",
          "space_memberships",
          "spaces",
          "asset_grants",
          "asset_grant_history",
          // Neither a foreign key to its library nor one to its organization: nothing would ever
          // fail over a row left here, it would just keep accumulating with a dangling library_id.
          "library_visibility_history",
          "group_membership_history",
          "knowledge_libraries",
          "users",
          "audit_log");

  private final JdbcTemplate jdbcTemplate;

  OwnOrganizationFixtures(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * @param organizationIds the organizations this test method created itself; the id of another
   *     class - or {@code Organization.DEFAULT_ID}, seeded once for the whole suite - is never
   *     passed here
   */
  public void removeOrganizations(UUID... organizationIds) {
    for (UUID organizationId : organizationIds) {
      for (String table : TABLES_IN_DELETION_ORDER) {
        jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organizationId);
      }
      jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }
  }
}
