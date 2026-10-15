package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.ChangelogSchema.Table;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How {@link ChangelogSchema} folds changelog SQL into tables and columns, and what it rejects. */
class ChangelogSchemaTest {

  private static final String FILE = "db/changelog/x/2026-10-15-x.yaml";

  @Test
  void readsColumnsAndSkipsConstraintsCommentsLiteralsAndPartitions() {
    Map<String, Table> schema =
        ChangelogSchema.of(
            FILE,
            """
            -- CREATE TABLE commented_out (a int);
            CREATE TABLE audit_log (
                event_id uuid NOT NULL,
                "Recorded_At" timestamp with time zone DEFAULT now() NOT NULL,
                label character varying(20) DEFAULT 'a, b (c)' NOT NULL,
                CONSTRAINT chk_label CHECK (label <> ''),
                PRIMARY KEY (event_id, "Recorded_At")
            ) PARTITION BY RANGE ("Recorded_At");
            CREATE TABLE audit_log_2026_01 PARTITION OF audit_log
                FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
            ALTER TABLE ONLY audit_log ADD CONSTRAINT uq UNIQUE (event_id, "Recorded_At");
            DO $$
            BEGIN
              EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log', 'p');
              EXECUTE format('DROP TABLE %s', 'p');
            END $$;
            """);

    assertThat(schema).containsOnlyKeys("audit_log");
    assertThat(schema.get("audit_log").columns())
        .containsOnlyKeys("event_id", "Recorded_At", "label")
        .containsValue(FILE);
  }

  @Test
  void foldsAddDropAndRenameOfColumnsAndTables() {
    Map<String, Table> schema =
        ChangelogSchema.of(
            FILE,
            """
            CREATE TABLE a (id uuid, old_name text, gone text);
            CREATE TABLE b (id uuid);
            ALTER TABLE a ADD COLUMN added text DEFAULT 'x', ADD IF NOT EXISTS other int;
            ALTER TABLE a DROP COLUMN gone, ALTER COLUMN id SET NOT NULL;
            ALTER TABLE a RENAME COLUMN old_name TO new_name;
            ALTER TABLE b RENAME TO c;
            ALTER TABLE IF EXISTS missing ADD COLUMN x int;
            DROP TABLE IF EXISTS c CASCADE;
            """);

    assertThat(schema).containsOnlyKeys("a");
    assertThat(schema.get("a").columns()).containsOnlyKeys("id", "new_name", "added", "other");
  }

  @Test
  void rejectsWhatItCannotRead() {
    assertThatThrownBy(() -> ChangelogSchema.of(FILE, "CREATE TABLE a AS SELECT 1 AS id;"))
        .hasMessageContaining("CREATE TABLE form");
    assertThatThrownBy(
            () ->
                ChangelogSchema.of(
                    FILE, "CREATE TABLE a (id int); ALTER TABLE a ATTACH PARTITION b FOR VALUES;"))
        .hasMessageContaining("ALTER TABLE action 'ATTACH PARTITION");
    assertThatThrownBy(
            () ->
                ChangelogSchema.of(
                    FILE, "DO $$ BEGIN EXECUTE 'ALTER TABLE a ADD COLUMN b int'; END $$;"))
        .hasMessageContaining("column DDL inside a procedural body");
    assertThatThrownBy(
            () -> ChangelogSchema.of(FILE, "DO $body$ BEGIN CREATE TABLE a (id int); END $body$;"))
        .hasMessageContaining("CREATE TABLE inside a procedural body");
    assertThatThrownBy(() -> ChangelogSchema.of(FILE, "ALTER TABLE missing ADD COLUMN x int;"))
        .hasMessageContaining("table missing is not known");
  }

  @Test
  void readsTheMasterChangelog() {
    Map<String, Table> schema = ChangelogSchema.read();

    assertThat(schema).containsKeys("users", "audit_log").doesNotContainKey("succession_reviews");
    assertThat(schema.get("users").file())
        .isEqualTo("db/changelog/identity/2026-09-27-baseline.yaml");
    assertThat(schema.get("capability_grants").columns())
        .containsEntry("scope", "db/changelog/rights/2026-10-03-capability-scope.yaml");
  }
}
