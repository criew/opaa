package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Dropping the citation position of library fields (ADR-0024, Entscheidung 7) on an existing
 * installation: every field survives with its Wirkstellen and its sort order, whether it carried a
 * position or not; citation_position, citation_enabled, their check and their unique index are
 * gone, so two fields that once held positions 1 and 2 are just two fields.
 */
class LibraryMetadataCitationPositionDropMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/knowledge/V2026.10.28.4__contract_drop_library_metadata_citation_position.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void everyFieldSurvivesWithoutTheCitationColumns() throws SQLException {
    UUID library = insertLibrary();
    UUID cited = insertField(library, "fassung", true, false, 1, 10);
    UUID secondCited = insertField(library, "gremium", false, true, 2, 20);
    UUID uncited = insertField(library, "projekt", true, true, null, 30);

    applyMigrations(connection, SCRIPT);

    assertThat(
            stringOf(
                "SELECT string_agg(field_key || ':' || filter_enabled || ':'"
                    + " || context_prefix_enabled || ':' || sort_order, ',' ORDER BY sort_order)"
                    + " FROM library_metadata_fields WHERE library_id = '"
                    + library
                    + "'"))
        .isEqualTo("fassung:true:false:10,gremium:false:true:20,projekt:true:true:30");
    assertThat(
            countWhere(
                "library_metadata_fields",
                "id IN ('" + cited + "', '" + secondCited + "', '" + uncited + "')"))
        .isEqualTo(3);
    assertThat(
            countWhere(
                "information_schema.columns",
                "table_schema = current_schema() AND table_name = 'library_metadata_fields'"
                    + " AND column_name IN ('citation_position', 'citation_enabled')"))
        .isZero();
    assertThat(
            countWhere(
                "pg_constraint",
                "conname = 'chk_library_metadata_fields_citation_position'"
                    + " AND connamespace = current_schema()::regnamespace"))
        .isZero();
    assertThat(
            countWhere(
                "pg_indexes",
                "schemaname = current_schema()"
                    + " AND indexname = 'uk_library_metadata_fields_citation_position'"))
        .isZero();
  }

  private UUID insertField(
      UUID library,
      String fieldKey,
      boolean filter,
      boolean contextPrefix,
      Integer citationPosition,
      int sortOrder)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
            + " filter_enabled, context_prefix_enabled, citation_enabled, citation_position,"
            + " sort_order, created_at, updated_at) VALUES ('"
            + id
            + "', '"
            + library
            + "', '"
            + fieldKey
            + "', 'Label', 'SELECT', "
            + filter
            + ", "
            + contextPrefix
            + ", "
            + (citationPosition != null)
            + ", "
            + (citationPosition == null ? "NULL" : citationPosition)
            + ", "
            + sortOrder
            + ", now(), now())");
    return id;
  }
}
