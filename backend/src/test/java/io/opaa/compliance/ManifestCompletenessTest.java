package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.compliance.MigrationSchema.Table;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gaps {@link ManifestCompleteness} reports, against the fixture manifest {@code valid/}: its
 * table {@code audit_log} lists {@code actor_ref, subject_ref} as personal and {@code event_id,
 * recorded_at} as other columns, {@code audit_retention_settings} is without personal reference
 * with {@code id, retention_months}.
 */
class ManifestCompletenessTest {

  private static final ComplianceManifest MANIFEST =
      ComplianceManifestLoader.load("classpath*:compliance-fixtures/valid/*.yaml");

  private static final String FILE = "db/migration/identity/V2026.10.15.1__x.sql";

  private static final String MATCHING =
      """
      CREATE TABLE audit_log (event_id uuid, recorded_at timestamptz, actor_ref uuid,
          subject_ref uuid);
      CREATE TABLE audit_retention_settings (id int, retention_months int);
      """;

  @Test
  void aSchemaMatchingTheManifestHasNoGap() {
    assertThat(ManifestCompleteness.schemaGaps(MANIFEST, schema(MATCHING))).isEmpty();
  }

  @Test
  void aNewColumnOfADataTableNamesBothListsWithTheColumnAppended() {
    List<String> gaps =
        ManifestCompleteness.schemaGaps(
            MANIFEST, schema(MATCHING + "ALTER TABLE audit_log ADD COLUMN phone text;"));

    assertThat(gaps)
        .containsExactly(
            "Table audit_log has columns without manifest entry: phone ("
                + FILE
                + ").\n"
                + "  In backend/src/main/resources/compliance/manifest/protocol.yaml, data entry"
                + " 'audit-log', table 'audit_log', either, if the column identifies a person or"
                + " records what a person did or when (id, name, address, free text, time of an"
                + " action), replace\n"
                + "        personalColumns: [actor_ref, subject_ref, phone]\n"
                + "  or otherwise replace\n"
                + "        nonPersonalColumns: [event_id, recorded_at, phone]");
  }

  @Test
  void aNewColumnOfATableWithoutPersonalReferenceAsksWhetherItStillHasNone() {
    List<String> gaps =
        ManifestCompleteness.schemaGaps(
            MANIFEST,
            schema(MATCHING + "ALTER TABLE audit_retention_settings ADD COLUMN updated_by uuid;"));

    assertThat(gaps)
        .singleElement()
        .asString()
        .contains(
            "nonPersonalTables, table 'audit_retention_settings', if no new column points at a"
                + " person, set:\n"
                + "    audit_retention_settings: [id, retention_months, updated_by]")
        .contains("personalColumns: [updated_by]");
  }

  @Test
  void aNewTableNamesBothPlacesWithItsColumnsAndTheLikelyAreaFile() {
    List<String> gaps =
        ManifestCompleteness.schemaGaps(
            MANIFEST,
            schema(MATCHING + "CREATE TABLE audit_exports (id uuid, requested_by uuid);"));

    assertThat(gaps)
        .singleElement()
        .asString()
        .startsWith(
            "Table audit_exports ("
                + FILE
                + ") has no manifest entry. Add it in"
                + " backend/src/main/resources/compliance/manifest/protocol.yaml")
        .contains("    nonPersonalTables:\n      audit_exports: [id, requested_by]")
        .contains("      - name: audit_exports\n        personalColumns: [");
  }

  @Test
  void anEntryWithoutCounterpartInTheSchemaIsAnOrphan() {
    List<String> gaps =
        ManifestCompleteness.schemaGaps(
            MANIFEST,
            schema(
                """
                CREATE TABLE audit_log (event_id uuid, recorded_at timestamptz, actor_ref uuid);
                """));

    assertThat(gaps)
        .containsExactly(
            "Column audit_log.subject_ref in"
                + " backend/src/main/resources/compliance/manifest/protocol.yaml, data entry"
                + " 'audit-log' does not exist in the schema. Remove 'subject_ref' there, or"
                + " rename it if the column was renamed.",
            "Table audit_retention_settings in"
                + " backend/src/main/resources/compliance/manifest/protocol.yaml,"
                + " nonPersonalTables does not exist in the schema. Remove it there; a data entry"
                + " left without tables and without storage outside the database goes as well.");
  }

  @Test
  void aMissingOrOrphanedEnumEntryNamesFileAndSnippet() {
    List<String> gaps =
        ManifestCompleteness.enumGaps(
            SystemRole.class,
            "roles",
            Map.of("USER", "roles", "FORMER_ROLE", "roles"),
            "seesAboutOthers: <...>");

    assertThat(gaps)
        .contains(
            "SystemRole.SYSTEM_ADMIN has no manifest entry. Add to"
                + " backend/src/main/resources/compliance/manifest/roles.yaml, section roles:\n"
                + "  - id: SYSTEM_ADMIN\n"
                + "    seesAboutOthers: <...>",
            "roles entry 'FORMER_ROLE' in"
                + " backend/src/main/resources/compliance/manifest/roles.yaml names no SystemRole"
                + " constant. Remove the entry, or rename its id if the constant was renamed.");
  }

  @Test
  void theReportClosesWithTheConvention() {
    assertThat(ManifestCompleteness.report("migration schema", List.of("gap")))
        .isEqualTo(
            "The compliance manifest does not match the migration schema (1 gap):\n\ngap\n\n"
                + "Convention: backend/docs/compliance-manifest.md");
  }

  private static Map<String, Table> schema(String sql) {
    return MigrationSchema.of(FILE, sql);
  }
}
