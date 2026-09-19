package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.local.LocalUserService;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * <p>All three links of the chain sub-query - accessor - {@code LocalUserService#blockers} are
 * checked, each by its own test: a link that is missing anywhere leaves the count silently short of
 * the schema.
 *
 * <p><b>One limit, deliberately left open.</b> The comparison is per {@code (table, column)}
 * mention, not per predicate: a narrowing condition beside it stays unseen - the {@code spaces}
 * sub-query already carries {@code AND is_default = false}, and a future {@code AND created_at >
 * :cutoff} would leave this guard green while the count lost blocking rows.
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

  /**
   * One {@code (SELECT count(*) FROM <table> WHERE <condition>) AS <alias>} of the native query -
   * the three things every comparison below is read from, taken from the same match so no test can
   * pair a table with another sub-query's alias.
   */
  private static final Pattern SUB_QUERY =
      Pattern.compile("\\(SELECT count\\(\\*\\) FROM (\\w+) WHERE ([^)]+)\\) AS (\\w+)");

  /** The columns such a condition compares against the account being deleted. */
  private static final Pattern USER_COLUMN = Pattern.compile("(\\w+) = :id");

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
                + " schema lets them go - the sub-query outlived its foreign key, and the refusal"
                + " it causes is one the schema no longer asks for (#1589, #1697)")
        .isEmpty();
  }

  /**
   * The chain's second link: a sub-query whose alias no accessor reads is dropped by the interface
   * projection without a word, so the count would look complete while the deletion still fails on
   * the constraint.
   */
  @Test
  void everyCountedTableHasAnAccessorOnTheProjection() {
    Set<String> expected = new LinkedHashSet<>(tableByAccessor().keySet());
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
   * The chain's third link: an accessor no branch of {@code LocalUserService#blockers} reads counts
   * for nothing - the deletion is then refused by the constraint without its table in the log,
   * exactly the state a missing sub-query causes. Proved by calling that method with a projection
   * counting one single table, so its branch has to name that table and no other.
   */
  @Test
  void everyAccessorIsReadByTheBranchOfBlockersThatNamesItsTable() throws Exception {
    Map<String, String> tables = tableByAccessor();
    Map<String, List<String>> named = new LinkedHashMap<>();
    Map<String, List<String>> expected = new LinkedHashMap<>();
    for (Map.Entry<String, String> accessor : tables.entrySet()) {
      named.put(accessor.getKey(), namedBlockers(countingOnly(accessor.getKey(), tables.keySet())));
      expected.put(accessor.getKey(), List.of(accessor.getValue()));
    }

    assertThat(named)
        .as(
            "every accessor of DeletionBlockers needs a branch in LocalUserService#blockers naming"
                + " its own table - one no branch reads counts silently for nothing, and the"
                + " deletion fails on the constraint without the table in the log (#1589, #1697)")
        .containsExactlyInAnyOrderEntriesOf(expected);
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

  /** The table each accessor stands for, both read from the same sub-query match. */
  private static Map<String, String> tableByAccessor() {
    Map<String, String> tables = new LinkedHashMap<>();
    Matcher subQuery = SUB_QUERY.matcher(normalizedQuery());
    while (subQuery.find()) {
      tables.put(accessorFor(subQuery.group(3)), subQuery.group(1));
    }
    return tables;
  }

  /** A projection counting one row for {@code counting} and none for any other accessor. */
  private static UserRepository.DeletionBlockers countingOnly(
      String counting, Set<String> accessors) {
    return (UserRepository.DeletionBlockers)
        Proxy.newProxyInstance(
            UserDeletionBlockerCoverageIntegrationTest.class.getClassLoader(),
            new Class<?>[] {UserRepository.DeletionBlockers.class},
            (proxy, method, args) -> {
              if (!accessors.contains(method.getName())) {
                throw new IllegalStateException("unexpected accessor " + method.getName());
              }
              return method.getName().equals(counting) ? 1L : 0L;
            });
  }

  /** The tables {@code LocalUserService#blockers} names for {@code counts} - a private method. */
  @SuppressWarnings("unchecked")
  private static List<String> namedBlockers(UserRepository.DeletionBlockers counts)
      throws ReflectiveOperationException {
    Method blockers =
        LocalUserService.class.getDeclaredMethod("blockers", UserRepository.DeletionBlockers.class);
    blockers.setAccessible(true);
    return (List<String>) blockers.invoke(null, counts);
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
