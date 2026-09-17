package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * <p>Both sides are read, never spelled out: the schema's side from {@code pg_catalog} of the live
 * Liquibase schema, the query's side from the {@link Query} annotation Spring Data actually
 * executes. The two comparisons are mutually load-bearing - a schema read that lost a reference
 * would surface in {@link #noSubQueryCountsBeyondTheRestrictingReferences()} as an unexpected
 * sub-query rather than pass unnoticed.
 *
 * <p><b>Two limits, deliberately left open.</b> The comparison is per {@code (table, column)}
 * mention, not per predicate: a narrowing condition beside it stays unseen - the {@code spaces}
 * sub-query already carries {@code AND is_default = false}, and a future {@code AND created_at >
 * :cutoff} would leave this guard green while the count lost blocking rows. And of the chain
 * sub-query - accessor - {@code LocalUserService#blockers}, only the first link is checked (by
 * {@link #everyCountedTableHasAnAccessorOnTheProjection()}); a count no branch of {@code blockers}
 * reads would still refuse without naming itself.
 */
@OpaaIntegrationTest
class UserDeletionBlockerCoverageIntegrationTest {

  /**
   * Every foreign key column that keeps the {@code users} row it points at alive - {@code
   * confdeltype} {@code r} (RESTRICT) or {@code a} (NO ACTION, how Postgres records a foreign key
   * declared without an explicit {@code ON DELETE}; it blocks just the same). Read from {@code
   * pg_catalog}, not {@code information_schema}: the latter reaches the referenced side only
   * through {@code unique_constraint_name}, which is {@code NULL} when a plain unique index backs
   * the target and which pins nothing to {@code users.id}, so a reference to another unique column
   * would drop out of the comparison instead of failing it. {@code conkey}/{@code confkey} are
   * unnested in step so a composite key resolves to the column actually facing the user; the {@code
   * organization_id} beside it carries the tenant, never the identity, and is the one pairing no
   * sub-query counts.
   */
  private static final String RESTRICTING_REFERENCES_TO_USERS =
      """
      SELECT child.relname AS referencing_table,
             child_column.attname AS referencing_column,
             parent_column.attname AS referenced_column,
             fk.conname AS constraint_name,
             CASE fk.confdeltype WHEN 'r' THEN 'RESTRICT' ELSE 'NO ACTION' END AS delete_rule
      FROM pg_constraint fk
      JOIN pg_class child ON child.oid = fk.conrelid
      JOIN LATERAL unnest(fk.conkey, fk.confkey) WITH ORDINALITY
           AS key_column(child_attnum, parent_attnum, ord) ON true
      JOIN pg_attribute child_column
        ON child_column.attrelid = fk.conrelid
       AND child_column.attnum = key_column.child_attnum
      JOIN pg_attribute parent_column
        ON parent_column.attrelid = fk.confrelid
       AND parent_column.attnum = key_column.parent_attnum
      WHERE fk.contype = 'f'
        AND fk.confrelid = 'users'::regclass
        AND fk.confdeltype IN ('r', 'a')
        AND parent_column.attname <> 'organization_id'
      """;

  /** One {@code (SELECT count(*) FROM <table> WHERE <condition>)} of the native query. */
  private static final Pattern SUB_QUERY =
      Pattern.compile("\\(SELECT count\\(\\*\\) FROM (\\w+) WHERE ([^)]+)\\)");

  /** The columns such a condition compares against the account being deleted. */
  private static final Pattern USER_COLUMN = Pattern.compile("(\\w+) = :id");

  /** The name a sub-query's result is returned under, and its accessor is derived from. */
  private static final Pattern RESULT_ALIAS = Pattern.compile("\\) AS (\\w+)");

  /**
   * References the query refuses a deletion for although the schema lets them go. <b>Open, not
   * intended:</b> ADR-0016 switched these two columns to {@code CASCADE} precisely so they would
   * stop blocking. That the count still blocks them is #1697's subject and the maintainer's
   * decision; this list records the deviation, it does not endorse it.
   */
  private static final Set<String> BLOCKED_BEYOND_THE_SCHEMA =
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
                + " deviation belongs in BLOCKED_BEYOND_THE_SCHEMA together with the issue that"
                + " decides it (#1589)")
        .containsExactlyInAnyOrderElementsOf(BLOCKED_BEYOND_THE_SCHEMA);
  }

  /**
   * The chain's second link: a sub-query whose alias no accessor reads is dropped by the interface
   * projection without a word, so the count would look complete while the deletion still fails on
   * the constraint.
   */
  @Test
  void everyCountedTableHasAnAccessorOnTheProjection() {
    Set<String> expected = new LinkedHashSet<>();
    Matcher alias = RESULT_ALIAS.matcher(normalizedQuery());
    while (alias.find()) {
      expected.add(accessorFor(alias.group(1)));
    }
    Set<String> declared =
        Arrays.stream(UserRepository.DeletionBlockers.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(expected)
        .as(
            "every alias of UserRepository#countDeletionBlockers needs its accessor on"
                + " DeletionBlockers and every accessor its alias - an alias without one is dropped"
                + " by the projection in silence, an accessor without one fails at call time"
                + " (#1589)")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  /**
   * The comparisons above are only worth anything against the schema Liquibase builds: Hibernate's
   * {@code ddl-auto} creates no foreign key at all for a plain {@code UUID} column without an
   * association mapping, which would leave them vacuously green.
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
   * The blocking references of the live schema, keyed {@code table.column}, valued by constraint
   * and delete rule. A key that faces {@code users} with more than one identifying column yields
   * one entry per column, each of which has to be counted.
   */
  private Map<String, String> restrictingReferences() {
    Map<String, String> references = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(RESTRICTING_REFERENCES_TO_USERS)) {
      references.put(
          row.get("referencing_table") + "." + row.get("referencing_column"),
          row.get("constraint_name")
              + " -> users."
              + row.get("referenced_column")
              + ", ON DELETE "
              + row.get("delete_rule"));
    }
    return references;
  }

  /** The references the native query counts, read from the annotation Spring Data executes. */
  private static Set<String> countedReferences() {
    Set<String> counted = new LinkedHashSet<>();
    Matcher subQuery = SUB_QUERY.matcher(normalizedQuery());
    while (subQuery.find()) {
      String table = subQuery.group(1);
      Matcher column = USER_COLUMN.matcher(subQuery.group(2));
      while (column.find()) {
        counted.add(table + "." + column.group(1));
      }
    }
    return counted;
  }

  /** {@code group_history} becomes {@code getGroupHistory}, the way the projection binds it. */
  private static String accessorFor(String alias) {
    StringBuilder accessor = new StringBuilder("get");
    for (String word : alias.split("_")) {
      accessor.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return accessor.toString();
  }

  private static String normalizedQuery() {
    try {
      return UserRepository.class
          .getMethod("countDeletionBlockers", UUID.class)
          .getAnnotation(Query.class)
          .value()
          .replaceAll("\\s+", " ");
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("countDeletionBlockers is gone, not just its sub-queries", e);
    }
  }
}
