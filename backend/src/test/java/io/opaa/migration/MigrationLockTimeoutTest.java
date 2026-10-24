package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every migration after the {@link RollingUpdateBoundary} bounds how long it waits for a lock
 * (backend/docs/migrationen.md, "lock_timeout"): its first statement is {@code SET LOCAL
 * lock_timeout = '<n>s'} of at most {@link #MAX_LOCK_TIMEOUT}, so an {@code ALTER TABLE} behind a
 * long read gives up instead of queueing every instance's queries behind its pending lock. A script
 * Flyway runs outside a transaction ({@code CONCURRENTLY}) sets it for the session and resets it as
 * its last statement. Reads the scripts only; {@code LockTimeoutMigrationTest} shows the effect on
 * a database.
 */
class MigrationLockTimeoutTest {

  static final Duration MAX_LOCK_TIMEOUT = Duration.ofSeconds(10);

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

  private static final Pattern IN_TRANSACTION =
      Pattern.compile("SET\\s+LOCAL\\s+lock_timeout\\s*(?:=|TO)\\s*'(\\d+)(ms|s)'", FLAGS);

  private static final Pattern FOR_THE_SESSION =
      Pattern.compile("SET\\s+(?:SESSION\\s+)?lock_timeout\\s*(?:=|TO)\\s*'(\\d+)(ms|s)'", FLAGS);

  private static final Pattern RESET = Pattern.compile("RESET\\s+lock_timeout", FLAGS);

  /** What makes Flyway run a PostgreSQL script outside a transaction. */
  private static final Pattern NON_TRANSACTIONAL = Pattern.compile("\\bCONCURRENTLY\\b", FLAGS);

  @Test
  void everyMigrationAfterTheBoundaryStartsWithABoundedLockTimeout() {
    List<String> offenders = new ArrayList<>();
    for (String script : Migrations.all()) {
      if (!RollingUpdateBoundary.before(script)) {
        String sql = Migrations.read(script);
        problem(sql, !NON_TRANSACTIONAL.matcher(sql).find())
            .ifPresent(problem -> offenders.add(script + ": " + problem));
      }
    }

    assertThat(offenders)
        .as(
            "every new migration starts with SET LOCAL lock_timeout = '5s' (at most %ss) - see"
                + " backend/docs/migrationen.md, \"lock_timeout\"",
            MAX_LOCK_TIMEOUT.toSeconds())
        .isEmpty();
  }

  @Test
  void theRuleReadsTheStatementThatRunsFirst() {
    assertThat(problem("SET LOCAL lock_timeout = '5s';\nALTER TABLE t ADD COLUMN c text;", true))
        .isEmpty();
    assertThat(problem("-- why\n/* and how */\n  set local LOCK_TIMEOUT to '500ms';", true))
        .isEmpty();
    assertThat(problem("ALTER TABLE t ADD COLUMN c text;\nSET LOCAL lock_timeout = '5s';", true))
        .hasValueSatisfying(
            problem -> assertThat(problem).contains("does not start with SET LOCAL lock_timeout"));
    assertThat(problem("-- SET LOCAL lock_timeout = '5s';\nALTER TABLE t ADD c text;", true))
        .hasValueSatisfying(
            problem -> assertThat(problem).contains("does not start with SET LOCAL lock_timeout"));
    assertThat(problem("SET LOCAL lock_timeout = '0';", true))
        .hasValueSatisfying(
            problem -> assertThat(problem).contains("does not start with SET LOCAL lock_timeout"));
    assertThat(problem("SET LOCAL lock_timeout = '30s';", true))
        .hasValueSatisfying(problem -> assertThat(problem).contains("longer than"));
    assertThat(problem("SET lock_timeout = '5s';\nCREATE INDEX CONCURRENTLY i ON t (c);", false))
        .hasValueSatisfying(
            problem -> assertThat(problem).contains("does not end with RESET lock_timeout"));
    assertThat(
            problem(
                "SET lock_timeout = '5s';\nCREATE INDEX CONCURRENTLY i ON t (c);\n"
                    + "RESET lock_timeout;",
                false))
        .isEmpty();
  }

  /** For the text of one script. */
  static Optional<String> problem(String sql, boolean inTransaction) {
    return problemAtTheStart(sql, inTransaction).or(() -> problemAtTheEnd(sql, inTransaction));
  }

  private static Optional<String> problemAtTheStart(String sql, boolean inTransaction) {
    String statement = firstStatement(sql);
    Matcher matcher = (inTransaction ? IN_TRANSACTION : FOR_THE_SESSION).matcher(statement);
    if (!matcher.matches()) {
      return Optional.of(
          "does not start with SET "
              + (inTransaction ? "LOCAL " : "")
              + "lock_timeout = '<n>s' but with: "
              + abbreviate(statement));
    }
    Duration timeout =
        matcher.group(2).equalsIgnoreCase("ms")
            ? Duration.ofMillis(Long.parseLong(matcher.group(1)))
            : Duration.ofSeconds(Long.parseLong(matcher.group(1)));
    if (timeout.isZero() || timeout.compareTo(MAX_LOCK_TIMEOUT) > 0) {
      return Optional.of("its lock_timeout of " + timeout + " is zero or longer than allowed");
    }
    return Optional.empty();
  }

  private static Optional<String> problemAtTheEnd(String sql, boolean inTransaction) {
    if (inTransaction || RESET.matcher(lastStatement(sql)).matches()) {
      return Optional.empty();
    }
    return Optional.of("runs outside a transaction and does not end with RESET lock_timeout");
  }

  /** The first statement, without the comments and blanks in front of it. */
  private static String firstStatement(String sql) {
    String rest = withoutLeadingComments(sql);
    int end = rest.indexOf(';');
    return (end < 0 ? rest : rest.substring(0, end)).strip();
  }

  private static String lastStatement(String sql) {
    String[] statements = sql.strip().split(";");
    for (int i = statements.length - 1; i >= 0; i--) {
      String statement = withoutLeadingComments(statements[i]).strip();
      if (!statement.isEmpty()) {
        return statement;
      }
    }
    return "";
  }

  private static String withoutLeadingComments(String sql) {
    String rest = sql.stripLeading();
    while (rest.startsWith("--") || rest.startsWith("/*")) {
      int end = rest.startsWith("--") ? rest.indexOf('\n') : rest.indexOf("*/") + 1;
      rest = end <= 0 ? "" : rest.substring(end + 1).stripLeading();
    }
    return rest;
  }

  private static String abbreviate(String statement) {
    String line = statement.replaceAll("\\s+", " ");
    return line.length() <= 80 ? line : line.substring(0, 80) + "...";
  }
}
