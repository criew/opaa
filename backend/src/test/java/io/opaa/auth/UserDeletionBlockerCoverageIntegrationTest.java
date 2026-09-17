package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds {@link UserRepository#countDeletionBlockers} to the invariant its Javadoc states (#1589):
 * every foreign key that refuses to let a {@code users} row go on {@code DELETE} has a sub-query
 * there. A reference the query misses is caught only by the constraint itself, so the refusal
 * cannot name its reason in the log.
 *
 * <p>Both sides are read, never spelled out: the schema's side from {@code information_schema} of
 * the live Liquibase schema, the query's side from the {@link Query} annotation Spring Data
 * actually executes. The two comparisons are mutually load-bearing - an under-reporting schema read
 * would surface in {@link #noSubQueryCountsBeyondTheRestrictingReferences()} as an unexpected
 * sub-query rather than pass unnoticed.
 */
@OpaaIntegrationTest
class UserDeletionBlockerCoverageIntegrationTest {

  /**
   * Every foreign key column pointing at {@code users.id} whose delete rule keeps the referenced
   * row alive. {@code NO ACTION} is included because that is how Postgres reports a foreign key
   * declared without an explicit {@code ON DELETE}, and it blocks the {@code DELETE} just as {@code
   * RESTRICT} does. Composite keys are resolved through {@code position_in_unique_constraint} so
   * that only the column facing {@code users.id} is read, not the {@code organization_id} beside
   * it.
   */
  private static final String RESTRICTING_REFERENCES_TO_USERS =
      """
      SELECT referencing.table_name AS referencing_table,
             referencing_column.column_name AS referencing_column,
             rc.constraint_name AS constraint_name,
             rc.delete_rule AS delete_rule
      FROM information_schema.referential_constraints rc
      JOIN information_schema.table_constraints referencing
        ON referencing.constraint_schema = rc.constraint_schema
       AND referencing.constraint_name = rc.constraint_name
      JOIN information_schema.table_constraints referenced
        ON referenced.constraint_schema = rc.unique_constraint_schema
       AND referenced.constraint_name = rc.unique_constraint_name
      JOIN information_schema.key_column_usage referencing_column
        ON referencing_column.constraint_schema = rc.constraint_schema
       AND referencing_column.constraint_name = rc.constraint_name
      JOIN information_schema.key_column_usage referenced_column
        ON referenced_column.constraint_schema = rc.unique_constraint_schema
       AND referenced_column.constraint_name = rc.unique_constraint_name
       AND referenced_column.ordinal_position = referencing_column.position_in_unique_constraint
      WHERE rc.constraint_schema = current_schema()
        AND referenced.table_schema = current_schema()
        AND referenced.table_name = 'users'
        AND referenced_column.column_name = 'id'
        AND rc.delete_rule IN ('RESTRICT', 'NO ACTION')
      """;

  /** One {@code (SELECT count(*) FROM <table> WHERE <condition>)} of the native query. */
  private static final Pattern SUB_QUERY =
      Pattern.compile("\\(SELECT count\\(\\*\\) FROM (\\w+) WHERE ([^)]+)\\)");

  /** The columns such a condition compares against the account being deleted. */
  private static final Pattern USER_COLUMN = Pattern.compile("(\\w+) = :id");

  /**
   * Sub-queries that deliberately block more than the schema does: #1509 switched these two columns
   * from {@code RESTRICT} to {@code CASCADE}, and that cascade would silently take away the grants
   * of still existing holders. The deletion therefore refuses instead of relying on it - the
   * explicit revocation that migration {@code 003} demands of an account deletion is not built yet.
   */
  private static final Set<String> DELIBERATELY_BLOCKED_BEYOND_THE_SCHEMA =
      Set.of(
          "diagnostic_impersonation_grants.granted_by_user_id",
          "diagnostic_impersonation_grants.revoked_by_user_id");

  @Autowired private JdbcTemplate jdbc;

  @Test
  void everyRestrictingReferenceToUsersIsCountedAsADeletionBlocker() {
    Map<String, String> restricting = restrictingReferences();
    Set<String> counted = countedReferences();

    List<String> missing =
        restricting.entrySet().stream()
            .filter(reference -> !counted.contains(reference.getKey()))
            .map(reference -> reference.getKey() + " (" + reference.getValue() + ")")
            .sorted()
            .toList();

    assertThat(missing)
        .as(
            "these foreign keys refuse to let a users row go, but"
                + " UserRepository#countDeletionBlockers has no sub-query for them - add"
                + " \"(SELECT count(*) FROM <table> WHERE <column> = :id) AS <alias>\" plus its"
                + " accessor and its entry in LocalUserService#blockers, otherwise the deletion"
                + " fails on the constraint without naming the reason (#1589)")
        .isEmpty();
  }

  @Test
  void noSubQueryCountsBeyondTheRestrictingReferences() {
    Set<String> restricting = restrictingReferences().keySet();

    List<String> beyond =
        countedReferences().stream()
            .filter(counted -> !restricting.contains(counted))
            .sorted()
            .toList();

    assertThat(beyond)
        .as(
            "UserRepository#countDeletionBlockers refuses a deletion for these columns, but the"
                + " schema lets them go - either the sub-query outlived its foreign key, or the"
                + " reason it outlives it belongs in DELIBERATELY_BLOCKED_BEYOND_THE_SCHEMA (#1589)")
        .containsExactlyInAnyOrderElementsOf(DELIBERATELY_BLOCKED_BEYOND_THE_SCHEMA);
  }

  /**
   * The comparison above is only worth anything against the schema Liquibase builds: Hibernate's
   * {@code ddl-auto} creates no foreign key at all for a plain {@code UUID} column without an
   * association mapping, which would leave both assertions vacuously green.
   */
  @Test
  void theComparedSchemaIsTheOneLiquibaseApplied() {
    Integer appliedChangeSets =
        jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class);

    assertThat(appliedChangeSets).as("no Liquibase change set applied").isNotNull().isPositive();
    assertThat(restrictingReferences())
        .as("no restricting foreign key to users found - the schema read is broken, not the schema")
        .isNotEmpty();
  }

  /**
   * The blocking references of the live schema, keyed {@code table.column}, valued by constraint.
   */
  private Map<String, String> restrictingReferences() {
    Map<String, String> references = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(RESTRICTING_REFERENCES_TO_USERS)) {
      references.put(
          row.get("referencing_table") + "." + row.get("referencing_column"),
          row.get("constraint_name") + ", ON DELETE " + row.get("delete_rule"));
    }
    return references;
  }

  /** The references the native query counts, read from the annotation Spring Data executes. */
  private static Set<String> countedReferences() {
    Set<String> counted = new LinkedHashSet<>();
    Matcher subQuery = SUB_QUERY.matcher(deletionBlockerQuery().replaceAll("\\s+", " "));
    while (subQuery.find()) {
      String table = subQuery.group(1);
      Matcher column = USER_COLUMN.matcher(subQuery.group(2));
      while (column.find()) {
        counted.add(table + "." + column.group(1));
      }
    }
    return counted;
  }

  private static String deletionBlockerQuery() {
    try {
      return UserRepository.class
          .getMethod("countDeletionBlockers", UUID.class)
          .getAnnotation(Query.class)
          .value();
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("countDeletionBlockers is gone, not just its sub-queries", e);
    }
  }
}
