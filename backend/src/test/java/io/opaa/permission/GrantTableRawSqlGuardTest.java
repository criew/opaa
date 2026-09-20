package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Raw SQL against the two grant tables must name the object the way the schema does since #1811:
 * {@code asset_type} plus {@code asset_id}, never the column that was renamed away. The compiler
 * cannot see inside a SQL string, and the source set that broke on exactly this ({@code evalTest})
 * is wired into neither {@code check} nor {@code build} - its first run is a nightly workflow on
 * {@code main}, long after the merge.
 *
 * <p>Reads every Java source root of this project, including the ones no CI job compiles, and
 * reassembles each SQL statement from the string literals a {@code +} chain concatenates - a
 * line-window scan would flag the neighbouring {@code library_visibility_history} cleanups, whose
 * own column keeps that name. {@code io.opaa.migration} is excluded by construction, not by an
 * allowlist of known offenders: a delta test proves what a changeset does to the <em>old</em>
 * schema and therefore has to write the old column name.
 */
class GrantTableRawSqlGuardTest {

  private static final List<Path> SOURCE_ROOTS =
      List.of(
          Path.of("src", "main", "java"),
          Path.of("src", "test", "java"),
          Path.of("src", "evalTest", "java"));

  private static final List<String> GRANT_TABLES = List.of("asset_grants", "asset_grant_history");

  /** Split, so this class's own source does not read as an offense to itself. */
  private static final String RENAMED_COLUMN = "library" + "_id";

  private static final Pattern SQL_VERB =
      Pattern.compile("\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|SELECT)\\b");

  /** What may stand between two literals of one concatenated statement. */
  private static final Pattern CONCATENATION = Pattern.compile("\\s*\\+\\s*");

  @Test
  void noSourceSetWritesTheGrantTablesWithTheRenamedColumn() {
    List<String> offenses = new ArrayList<>();
    for (Path root : SOURCE_ROOTS) {
      assertThat(Files.isDirectory(root)).as("source root %s must exist", root).isTrue();
      collectOffensesUnder(root, offenses);
    }

    assertThat(offenses)
        .as(
            "raw SQL against %s must name its object by asset_type and asset_id (#1811)",
            GRANT_TABLES)
        .isEmpty();
  }

  /** Guards the premise: the scan must actually reassemble statements, not find nothing at all. */
  @Test
  void theScanSeesTheRawStatementsAgainstTheGrantTables() {
    List<String> statements = new ArrayList<>();
    for (Path root : SOURCE_ROOTS) {
      collectGrantStatementsUnder(root, statements);
    }

    assertThat(statements)
        .as("the source tree does contain raw SQL against the grant tables")
        .isNotEmpty();
    assertThat(statements).anyMatch(statement -> statement.contains("INSERT INTO asset_grants"));
  }

  private static void collectOffensesUnder(Path root, List<String> offenses) {
    walk(
        root,
        file -> {
          for (String statement : sqlStatementsOf(read(file))) {
            if (namesAGrantTable(statement) && statement.contains(RENAMED_COLUMN)) {
              offenses.add(file + " -> " + statement);
            }
          }
        });
  }

  private static void collectGrantStatementsUnder(Path root, List<String> statements) {
    walk(
        root,
        file ->
            sqlStatementsOf(read(file)).stream()
                .filter(GrantTableRawSqlGuardTest::namesAGrantTable)
                .forEach(statements::add));
  }

  private static void walk(Path root, Consumer<Path> visitor) {
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/io/opaa/migration/"))
          .sorted()
          .forEach(visitor);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean namesAGrantTable(String statement) {
    return GRANT_TABLES.stream().anyMatch(statement::contains);
  }

  /**
   * Every SQL statement of a source file, each reassembled from the literals its {@code +} chain
   * concatenates. A literal that carries no SQL verb is prose (a message, a constant) and is
   * dropped.
   */
  private static List<String> sqlStatementsOf(String source) {
    String code = stripComments(source);
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int previousEnd = -1;
    int i = 0;
    // Hand-written rather than a literal-matching regex: that one backtracks per character and
    // overflows the stack on this codebase's longest SQL strings and text blocks.
    while (i < code.length()) {
      if (code.charAt(i) != '"') {
        i++;
        continue;
      }
      int start = i;
      StringBuilder literal = new StringBuilder();
      if (code.startsWith("\"\"\"", i)) {
        i += 3;
        while (i < code.length() && !code.startsWith("\"\"\"", i)) {
          literal.append(code.charAt(i++));
        }
        i = Math.min(code.length(), i + 3);
      } else {
        i++;
        while (i < code.length() && code.charAt(i) != '"' && code.charAt(i) != '\n') {
          if (code.charAt(i) == '\\' && i + 1 < code.length()) {
            i++;
          }
          literal.append(code.charAt(i++));
        }
        i = Math.min(code.length(), i + 1);
      }
      boolean continues =
          previousEnd >= 0 && CONCATENATION.matcher(code.substring(previousEnd, start)).matches();
      if (!continues) {
        addIfSql(statements, current.toString());
        current.setLength(0);
      }
      current.append(literal);
      previousEnd = i;
    }
    addIfSql(statements, current.toString());
    return statements;
  }

  private static void addIfSql(List<String> statements, String candidate) {
    if (!candidate.isEmpty() && SQL_VERB.matcher(candidate).find()) {
      statements.add(candidate);
    }
  }

  /** Blanks comments while keeping every offset, so a literal inside one is never picked up. */
  private static String stripComments(String source) {
    char[] out = source.toCharArray();
    int i = 0;
    while (i < out.length) {
      if (out[i] == '/' && i + 1 < out.length && out[i + 1] == '/') {
        while (i < out.length && out[i] != '\n') {
          out[i++] = ' ';
        }
      } else if (out[i] == '/' && i + 1 < out.length && out[i + 1] == '*') {
        while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
          if (out[i] != '\n') {
            out[i] = ' ';
          }
          i++;
        }
        for (int end = 0; end < 2 && i < out.length; end++) {
          out[i++] = ' ';
        }
      } else {
        i++;
      }
    }
    return new String(out);
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
