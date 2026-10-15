package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.ChangelogSchema.Table;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  void dropsThePublicSchemaAndHonoursQuotedNames() {
    Map<String, Table> schema =
        ChangelogSchema.of(
            FILE,
            """
            CREATE TABLE public.users (id uuid);
            ALTER TABLE public.users ADD COLUMN phone text;
            ALTER TABLE users ADD COLUMN "Phone" text, ADD "Mobile";
            /* outer /* nested */ CREATE TABLE hidden (a int); */
            ALTER/* inline */TABLE users ADD COLUMN fax text;
            """);

    assertThat(schema).containsOnlyKeys("users");
    assertThat(schema.get("users").columns())
        .containsOnlyKeys("id", "phone", "Phone", "Mobile", "fax");
  }

  /** Each case is a probe changelog the reader must refuse rather than pass over. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadable")
  void rejectsWhatItCannotRead(String sql, String message) {
    assertThatThrownBy(() -> ChangelogSchema.of(FILE, "CREATE TABLE users (id uuid);\n" + sql))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }

  static Stream<Arguments> unreadable() {
    String body = "table DDL inside a procedural body";
    return Stream.of(
        Arguments.of("CREATE TABLE a AS SELECT 1 AS id;", "CREATE TABLE form"),
        Arguments.of("CREATE TABLE a (LIKE users);", "LIKE"),
        Arguments.of("CREATE TABLE a (id int) INHERITS (users);", "CREATE TABLE clause"),
        Arguments.of("ALTER TABLE users ATTACH PARTITION b FOR VALUES;", "ATTACH PARTITION"),
        Arguments.of("ALTER TABLE users;", "ALTER TABLE form"),
        Arguments.of("ALTER TABLE other.users ADD COLUMN phone text;", "outside the schema"),
        Arguments.of("CREATE TABLE other.t (id int);", "outside the schema"),
        Arguments.of("DROP TABLE other.users;", "outside the schema"),
        Arguments.of("ALTER TABLE users ADD COLUMN;", "column name 'COLUMN'"),
        Arguments.of("ALTER TABLE missing ADD COLUMN x int;", "table missing is not known"),
        Arguments.of("DO $$ BEGIN ALTER TABLE users ADD phone text; END $$;", body),
        Arguments.of("DO $$ BEGIN EXECUTE 'ALTER TABLE users RENAME a TO b'; END $$;", body),
        Arguments.of("DO $$ BEGIN EXECUTE 'RENAME a TO b'; END $$;", body),
        Arguments.of("DO $body$ BEGIN CREATE TABLE a (id int); END $body$;", body),
        Arguments.of("DO $$ BEGIN CREATE UNLOGGED TABLE a (id int); END $$;", body),
        Arguments.of("DO $$ BEGIN DROP TABLE users; END $$;", body),
        Arguments.of(
            "CREATE FUNCTION f() RETURNS void LANGUAGE sql AS $$ CREATE SCHEMA s $$;", body),
        Arguments.of("SELECT * INTO users_copy FROM users;", "statement"),
        Arguments.of("CREATE SCHEMA s CREATE TABLE t (id int);", "statement"),
        Arguments.of("CREATE MATERIALIZED VIEW v AS SELECT id FROM users;", "statement"),
        Arguments.of("/* unterminated /* nested */ CREATE TABLE t (id int);", "block comment"));
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
