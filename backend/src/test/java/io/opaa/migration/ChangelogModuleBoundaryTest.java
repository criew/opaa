package io.opaa.migration;

import static io.opaa.architecture.ModularArchitecture.Module.ASSISTANT;
import static io.opaa.architecture.ModularArchitecture.Module.CONNECTIONS;
import static io.opaa.architecture.ModularArchitecture.Module.CONNECTORS;
import static io.opaa.architecture.ModularArchitecture.Module.EXTERNAL;
import static io.opaa.architecture.ModularArchitecture.Module.FOUNDATION;
import static io.opaa.architecture.ModularArchitecture.Module.IDENTITY;
import static io.opaa.architecture.ModularArchitecture.Module.KNOWLEDGE;
import static io.opaa.architecture.ModularArchitecture.Module.RIGHTS;
import static io.opaa.architecture.ModularArchitecture.Module.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.architecture.ModularArchitecture;
import io.opaa.architecture.ModularArchitecture.Module;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * The module boundary of the changelog: applied module directory by module directory, in master
 * order, each module creates and changes only its own tables, and its triggers, functions and
 * foreign keys reach only modules {@link ModularArchitecture#ALLOWED_MODULE_EDGES} lets it depend
 * on (see {@link ModuleBoundaryCheck}). {@link #TABLE_MODULES} assigns every table to a module; a
 * new table without an entry fails here, and so does an entry that no longer matches the package of
 * the table's entity.
 */
class ChangelogModuleBoundaryTest extends AbstractMigrationTest {

  /**
   * Every table of the schema and its module - the module whose package holds its entity, and whose
   * changelog directory creates it. {@code vector_store} is created by Spring AI, not Liquibase; it
   * belongs to knowledge, whose changelog indexes it.
   */
  static final Map<String, Module> TABLE_MODULES =
      tables(
          Map.entry(FOUNDATION, List.of("organizations")),
          Map.entry(
              IDENTITY,
              List.of(
                  "users",
                  "oidc_providers",
                  "oidc_provider_seed_marker",
                  "local_credentials",
                  "local_refresh_tokens",
                  "local_revoked_tokens",
                  "local_action_tokens",
                  "local_auth_settings",
                  "local_admin_seed_marker",
                  "branding_settings",
                  "mail_settings",
                  "mail_templates",
                  "notifications",
                  "audit_log",
                  "audit_actor_pseudonyms",
                  "audit_retention_settings",
                  "audit_incident_scope_grants")),
          Map.entry(
              RIGHTS,
              List.of(
                  "groups",
                  "group_memberships",
                  "group_stewards",
                  "directory_sync_status",
                  "directory_sync_pending_plans",
                  "directory_connectors",
                  "assets",
                  "asset_visibility_history",
                  "asset_grants",
                  "asset_grant_history",
                  "asset_ownership_history",
                  "asset_favorites",
                  "capability_grants",
                  "capability_grant_history",
                  "group_membership_history",
                  "permission_history_retention_settings",
                  "permission_transfers",
                  "permission_transfer_objects",
                  "account_state_history",
                  "succession_cases",
                  "succession_reviews")),
          Map.entry(
              KNOWLEDGE,
              List.of(
                  "knowledge_libraries",
                  "library_folders",
                  "documents",
                  "indexing_jobs",
                  "indexing_run_events",
                  "source_sync_state",
                  "chunk_full_text",
                  "document_keywords",
                  "document_metadata_values",
                  "document_type_vocabulary",
                  "document_type_synonyms",
                  "document_type_suffixes",
                  "document_type_suffix_exclusions",
                  "library_metadata_fields",
                  "library_metadata_field_values",
                  "library_metadata_schema_changes",
                  "metadata_model_extraction_stats",
                  "metadata_model_rejections",
                  "llm_models",
                  "llm_model_seed_marker",
                  "vector_store")),
          Map.entry(CONNECTORS, List.of("rss_feed_state")),
          Map.entry(
              CONNECTIONS,
              List.of(
                  "connection_profiles",
                  "library_connections",
                  "connector_type_policies",
                  "connection_log",
                  "connection_log_retention_settings",
                  "connection_profile_requests")),
          Map.entry(
              WORKSPACE,
              List.of(
                  "spaces",
                  "space_memberships",
                  "space_membership_history",
                  "space_asset_associations",
                  "diagnostic_impersonation_grants",
                  "diagnostic_context_log",
                  "diagnostic_context_retention_settings")),
          Map.entry(
              ASSISTANT,
              List.of(
                  "chats",
                  "chat_messages",
                  "chat_library_references",
                  "chat_note_items",
                  "chat_personal_marks",
                  "prompt_libraries",
                  "prompts")),
          Map.entry(
              EXTERNAL,
              List.of(
                  "external_access_settings",
                  "external_access_tokens",
                  "external_access_token_libraries")));

  private Connection connection;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-empty.yaml");
  }

  @BeforeEach
  void openConnection() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void closeConnection() throws SQLException {
    connection.close();
  }

  /**
   * Starts from a {@code vector_store} as Spring AI leaves it, so the knowledge module's guarded
   * index changeSets run and are judged too.
   */
  @Test
  void everyModuleDirectoryKeepsToItsModule() throws Exception {
    execute("CREATE TABLE vector_store (id uuid PRIMARY KEY, content text, metadata jsonb)");
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, TABLE_MODULES);

    for (Map.Entry<String, List<String>> module : filesPerModuleDirectory().entrySet()) {
      check.step(
          Module.valueOf(module.getKey().toUpperCase(Locale.ROOT)),
          () -> {
            for (String file : module.getValue()) {
              applyChangelog(connection, file);
            }
          });
    }

    assertThat(check.violations()).isEmpty();
  }

  /**
   * The table of an entity belongs to the module of the entity's package, and so does a collection
   * table the entity declares.
   */
  @Test
  void everyEntityTableBelongsToTheModuleOfItsPackage() {
    ModularArchitecture architecture = new ModularArchitecture("io.opaa");
    List<String> mismatches = new ArrayList<>();
    for (Class<?> entity : entityClasses()) {
      Module packageModule = architecture.moduleOfPackage(entity.getPackageName());
      List<String> entityTables = new ArrayList<>();
      entityTables.add(entity.getAnnotation(Table.class).name());
      for (Field field : entity.getDeclaredFields()) {
        CollectionTable collectionTable = field.getAnnotation(CollectionTable.class);
        if (collectionTable != null) {
          entityTables.add(collectionTable.name());
        }
      }
      for (String table : entityTables) {
        Module tableModule = TABLE_MODULES.get(table);
        if (tableModule != packageModule) {
          mismatches.add(
              table
                  + " of "
                  + entity.getName()
                  + " is assigned to "
                  + tableModule
                  + ", its package to "
                  + packageModule);
        }
      }
    }

    assertThat(mismatches).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // The check itself: every rule fires
  // ---------------------------------------------------------------------------------------------

  private static final Map<String, Module> FIXTURE_TABLES =
      Map.of(
          "fx_org", FOUNDATION,
          "fx_account", IDENTITY,
          "fx_grant", RIGHTS);

  @Test
  void aModuleCreatingAForeignTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)"));
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactly("IDENTITY creates table fx_grant, which belongs to module RIGHTS");
  }

  @Test
  void aTableWithoutAModuleIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, Map.of());

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_unknown (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactly(
            "table fx_unknown (created by FOUNDATION) is assigned to no module in"
                + " ChangelogModuleBoundaryTest.TABLE_MODULES");
  }

  @Test
  void anAssignedTableNoChangelogCreatesIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, Map.of("fx_org", FOUNDATION));

    assertThat(check.violations())
        .containsExactly("fx_org is assigned to a module but no changelog creates it");
  }

  @Test
  void aHigherModuleChangingALowerTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute("ALTER TABLE fx_org ADD COLUMN grant_count integer");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));
    check.step(IDENTITY, () -> execute("DROP TABLE fx_grant"));

    assertThat(check.violations())
        .containsExactlyInAnyOrder(
            "RIGHTS changes table fx_org of module FOUNDATION",
            "IDENTITY drops table fx_grant of module RIGHTS",
            "fx_grant is assigned to a module but no changelog creates it");
  }

  @Test
  void aColumnGrantOnALowerTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY, name text)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute("GRANT UPDATE (name) ON fx_org TO PUBLIC");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactly("RIGHTS changes table fx_org of module FOUNDATION");
  }

  @Test
  void enablingRowSecurityOnALowerTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute("ALTER TABLE fx_org ENABLE ROW LEVEL SECURITY");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactly("RIGHTS changes table fx_org of module FOUNDATION");
  }

  @Test
  void aPolicyOnALowerTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute("CREATE POLICY fx_org_visible ON fx_org FOR SELECT USING (true)");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactly("RIGHTS changes table fx_org of module FOUNDATION");
  }

  /** A module may read a lower table, but its functions write only its own. */
  @Test
  void aFunctionWritingALowerTableIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute(
              "CREATE FUNCTION fx_count_orgs() RETURNS bigint LANGUAGE sql AS"
                  + " 'SELECT count(*) FROM fx_org'");
          execute(
              "CREATE FUNCTION fx_add_org() RETURNS void LANGUAGE plpgsql AS $$ BEGIN"
                  + " INSERT INTO fx_org (id) VALUES (gen_random_uuid()); END $$");
          execute(
              "CREATE FUNCTION fx_clear_orgs() RETURNS void LANGUAGE sql BEGIN ATOMIC"
                  + " DELETE FROM fx_org; END");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactlyInAnyOrder(
            "RIGHTS function fx_add_org() writes table fx_org of module FOUNDATION",
            "RIGHTS function fx_clear_orgs() writes table fx_org of module FOUNDATION");
  }

  @Test
  void aForeignKeyPointingUpwardIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));
    check.step(
        FOUNDATION,
        () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY REFERENCES fx_account (id))"));
    check.step(
        RIGHTS,
        () -> execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY REFERENCES fx_account (id))"));

    assertThat(check.violations())
        .containsExactly(
            "foreign key fx_org_id_fkey points upward from fx_org (FOUNDATION) to fx_account"
                + " (IDENTITY)");
  }

  /**
   * A module may hang a trigger on a table of a module it depends on - the rights module on {@code
   * organizations} - but not on a higher one, and its function may name only tables it may reach.
   */
  @Test
  void aTriggerMayReachDownwardButNotUpward() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(FOUNDATION, () -> execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)"));
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute(
              "CREATE FUNCTION fx_seed_grants() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                  + " INSERT INTO fx_grant (id) VALUES (NEW.id); RETURN NEW; END $$");
          execute(
              "CREATE TRIGGER trg_fx_org_seed_grants AFTER INSERT ON fx_org FOR EACH ROW"
                  + " EXECUTE FUNCTION fx_seed_grants()");
        });
    check.step(
        IDENTITY,
        () -> {
          execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)");
          execute(
              "CREATE FUNCTION fx_touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                  + " RETURN NEW; END $$");
          execute(
              "CREATE TRIGGER trg_fx_grant_touch BEFORE INSERT ON fx_grant FOR EACH ROW"
                  + " EXECUTE FUNCTION fx_touch()");
          execute(
              "CREATE FUNCTION fx_count_grants() RETURNS bigint LANGUAGE sql AS"
                  + " 'SELECT count(*) FROM fx_grant'");
        });

    assertThat(check.violations())
        .containsExactlyInAnyOrder(
            "IDENTITY creates trigger fx_grant.trg_fx_grant_touch on table fx_grant of module"
                + " RIGHTS, which it may not depend on",
            "IDENTITY function fx_count_grants() names table fx_grant of module RIGHTS, which it"
                + " may not depend on");
  }

  @Test
  void droppingOrReplacingAnotherModulesTriggerOrFunctionIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, FIXTURE_TABLES);

    check.step(
        FOUNDATION,
        () -> {
          execute("CREATE TABLE fx_org (id uuid PRIMARY KEY)");
          execute(
              "CREATE FUNCTION fx_touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                  + " RETURN NEW; END $$");
          execute(
              "CREATE TRIGGER trg_fx_org_touch BEFORE INSERT ON fx_org FOR EACH ROW"
                  + " EXECUTE FUNCTION fx_touch()");
        });
    check.step(
        RIGHTS,
        () -> {
          execute("CREATE TABLE fx_grant (id uuid PRIMARY KEY)");
          execute("DROP TRIGGER trg_fx_org_touch ON fx_org");
          execute(
              "CREATE OR REPLACE FUNCTION fx_touch() RETURNS trigger LANGUAGE plpgsql AS $$"
                  + " BEGIN NEW.id := NEW.id; RETURN NEW; END $$");
        });
    check.step(IDENTITY, () -> execute("CREATE TABLE fx_account (id uuid PRIMARY KEY)"));

    assertThat(check.violations())
        .containsExactlyInAnyOrder(
            "RIGHTS drops trigger fx_org.trg_fx_org_touch, created by module FOUNDATION",
            "RIGHTS replaces function fx_touch(), created by module FOUNDATION");
  }

  // ---------------------------------------------------------------------------------------------

  /** The master's files grouped by module directory, both in master order. */
  private static Map<String, List<String>> filesPerModuleDirectory() {
    Map<String, List<String>> modules = new LinkedHashMap<>();
    for (String file : MasterChangelog.files()) {
      modules.computeIfAbsent(MasterChangelog.moduleOf(file), key -> new ArrayList<>()).add(file);
    }
    return modules;
  }

  private static List<Class<?>> entityClasses() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    List<Class<?>> entities = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa")) {
      try {
        entities.add(Class.forName(candidate.getBeanClassName()));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
    assertThat(entities).as("entities found on the class path").isNotEmpty();
    return entities;
  }

  @SafeVarargs
  private static Map<String, Module> tables(Map.Entry<Module, List<String>>... modules) {
    Map<String, Module> tables = new LinkedHashMap<>();
    Stream.of(modules)
        .forEach(
            module ->
                module
                    .getValue()
                    .forEach(
                        table -> {
                          if (tables.put(table, module.getKey()) != null) {
                            throw new IllegalStateException(table + " is assigned twice");
                          }
                        }));
    return tables;
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
