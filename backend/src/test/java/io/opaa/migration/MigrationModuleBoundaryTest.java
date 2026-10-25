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
 * The module boundary of the migrations: applied script by script, in version order, each script of
 * a module directory creates and changes only the tables of its module, and its triggers, functions
 * and foreign keys reach only modules {@link ModularArchitecture#ALLOWED_MODULE_EDGES} lets it
 * depend on (see {@link ModuleBoundaryCheck}). {@link #TABLE_MODULES} assigns every table to a
 * module; a new table without an entry fails here, and so does an entry that no longer matches the
 * package of the table's entity.
 */
class MigrationModuleBoundaryTest extends AbstractMigrationTest {

  /**
   * Every table of the schema and its module - the module whose package holds its entity, and whose
   * migration directory creates it. {@code vector_store} belongs to knowledge, whose migration
   * creates and indexes it; Spring AI only reads and writes it.
   */
  static final Map<String, Module> TABLE_MODULES =
      tables(
          Map.entry(
              FOUNDATION,
              List.of(
                  "organizations",
                  "outbound_proxy_settings",
                  "backend_instances",
                  "backend_instance_fingerprint_key",
                  "shedlock",
                  "installation_counters")),
          Map.entry(
              IDENTITY,
              List.of(
                  "users",
                  "oidc_providers",
                  "oidc_provider_seed_marker",
                  "oidc_provider_removals",
                  "local_credentials",
                  "admin_review_reminders",
                  "local_refresh_tokens",
                  "local_revoked_tokens",
                  "local_action_tokens",
                  "local_auth_settings",
                  "local_admin_seed_marker",
                  "branding_settings",
                  "mail_settings",
                  "mail_templates",
                  "mail_outbox",
                  "notifications",
                  "audit_log",
                  "audit_actor_pseudonyms",
                  "audit_retention_settings",
                  "siem_export_cursor",
                  "audit_chain_checkpoints",
                  "audit_chain_seals",
                  "audit_incident_scope_grants",
                  "audit_incident_scope_case_counts",
                  "audit_incident_scope_monthly_counts",
                  "self_disclosure_settings",
                  "self_disclosure_usage",
                  "link_target_settings")),
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
                  "succession_cases")),
          Map.entry(
              KNOWLEDGE,
              List.of(
                  "knowledge_libraries",
                  "library_folders",
                  "documents",
                  "indexing_jobs",
                  "indexing_run_events",
                  "source_sync_state",
                  "source_sync_revisits",
                  "source_event_batches",
                  "source_sync_presence",
                  "chunk_full_text",
                  "embedding_index",
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
                  "installation_state",
                  "installation_steps",
                  "embedding_model",
                  "private_storage_quota_settings",
                  "full_sync_rhythm_settings",
                  "vector_store",
                  "model_role_observations",
                  "library_maintenance_skips",
                  "embedding_rate_measurements")),
          Map.entry(CONNECTORS, List.of("rss_feed_state")),
          Map.entry(
              CONNECTIONS,
              List.of(
                  "connection_profiles",
                  "library_connections",
                  "connector_type_policies",
                  "connection_log",
                  "connection_log_retention_settings",
                  "connected_accounts",
                  "connection_tokens",
                  "connection_person_states",
                  "connection_authorizations",
                  "connection_grant_revocations")),
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
                  "search_status_probes",
                  "chats",
                  "chat_messages",
                  "chat_library_references",
                  "chat_note_items",
                  "chat_personal_marks",
                  "chat_cleanup_holds",
                  "chat_locked_account_cleanup_state",
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
  protected List<String> baseFixtureMigrations() {
    return List.of();
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

  @Test
  void everyMigrationKeepsToTheModuleOfItsDirectory() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, TABLE_MODULES);

    for (String script : Migrations.all()) {
      check.step(
          Module.valueOf(Migrations.moduleOf(script).toUpperCase(Locale.ROOT)),
          () -> applyMigrations(connection, script));
    }
    // vector_store is created by the installation, not a migration (ADR-0060), as knowledge's table
    check.step(
        Module.KNOWLEDGE,
        () -> Migrations.createVectorTable(connection, connection.getSchema(), 1024));

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
                + " MigrationModuleBoundaryTest.TABLE_MODULES");
  }

  @Test
  void anAssignedTableNoMigrationCreatesIsAViolation() throws Exception {
    ModuleBoundaryCheck check = new ModuleBoundaryCheck(connection, Map.of("fx_org", FOUNDATION));

    assertThat(check.violations())
        .containsExactly("fx_org is assigned to a module but no migration creates it");
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
            "fx_grant is assigned to a module but no migration creates it");
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
