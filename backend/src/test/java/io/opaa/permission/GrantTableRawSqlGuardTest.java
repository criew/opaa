package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
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
 *
 * <p><b>What it cannot see</b>, named rather than left to be rediscovered: a statement assembled
 * from something other than adjacent literals - a table or column name behind a constant ({@code
 * "... WHERE asset_id IN " + OWN_LIBRARIES}, whose literal half this does read), {@code
 * String.format}/{@code formatted}, {@code String.join}, or a value built at runtime. A statement
 * split so that the table name and the renamed column land in different literals of different
 * chains would also pass. {@link #theScanFindsEveryFileThatNamesAGrantTableInAStatement} keeps the
 * reassembler honest about the files it does reach; it cannot vouch for these shapes.
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
      Pattern.compile(
          "\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|SELECT)\\b", Pattern.CASE_INSENSITIVE);

  /** What may stand between two literals of one concatenated statement. */
  private static final Pattern CONCATENATION = Pattern.compile("\\s*\\+\\s*");

  @Test
  void noSourceSetWritesTheGrantTablesWithTheRenamedColumn() {
    List<String> offenses = new ArrayList<>();
    forEachSourceFile(
        (file, statements) ->
            statements.stream()
                .filter(GrantTableRawSqlGuardTest::namesAGrantTable)
                .filter(statement -> statement.contains(RENAMED_COLUMN))
                .forEach(statement -> offenses.add(file + " -> " + statement)));

    assertThat(offenses)
        .as(
            "raw SQL against %s must name its object by asset_type and asset_id (#1811)",
            GRANT_TABLES)
        .isEmpty();
  }

  /**
   * The premise, as an equality rather than a floor: every file a plain text search finds - a line
   * carrying a string literal, a grant table and a SQL verb - must be a file the reassembler found
   * a statement in. A reassembler that silently stopped merging concatenations, or stopped
   * recognising a verb, would otherwise keep passing on an ever smaller share of the tree.
   */
  @Test
  void theScanFindsEveryFileThatNamesAGrantTableInAStatement() {
    Set<String> reassembled = new LinkedHashSet<>();
    forEachSourceFile(
        (file, statements) -> {
          if (statements.stream().anyMatch(GrantTableRawSqlGuardTest::namesAGrantTable)) {
            reassembled.add(file.toString());
          }
        });

    Set<String> textSearch = new LinkedHashSet<>();
    forEachSourceFile(
        (file, statements) -> {
          if (hasAGrantStatementLine(read(file))) {
            textSearch.add(file.toString());
          }
        });

    assertThat(textSearch)
        .as("the source tree does carry raw SQL against these tables")
        .isNotEmpty();
    assertThat(reassembled)
        .as("every file a plain text search attributes a grant statement to must be reassembled")
        .containsExactlyInAnyOrderElementsOf(textSearch);
  }

  /** Independent of the reassembler on purpose: one line, no concatenation, no comment handling. */
  private static boolean hasAGrantStatementLine(String source) {
    for (String line : source.split("\n", -1)) {
      if (line.indexOf('"') >= 0
          && GRANT_TABLES.stream().anyMatch(line::contains)
          && SQL_VERB.matcher(line).find()) {
        return true;
      }
    }
    return false;
  }

  private static void forEachSourceFile(BiConsumer<Path, List<String>> visitor) {
    for (Path root : SOURCE_ROOTS) {
      assertThat(Files.isDirectory(root)).as("source root %s must exist", root).isTrue();
      try (Stream<Path> files = Files.walk(root)) {
        files
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .filter(path -> !path.toString().replace('\\', '/').contains("/io/opaa/migration/"))
            .sorted()
            .forEach(file -> visitor.accept(file, sqlStatementsOf(read(file))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static boolean namesAGrantTable(String statement) {
    return GRANT_TABLES.stream().anyMatch(statement::contains);
  }

  /**
   * Every SQL statement of a source file, each reassembled from the literals its {@code +} chain
   * concatenates. A literal that carries no SQL verb is prose (a message, a constant) and is
   * dropped. One pass over the source: comments and literals are recognised by the same scanner, so
   * neither a {@code //} inside a literal nor a literal inside a comment is mistaken for the other.
   */
  private static List<String> sqlStatementsOf(String source) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    StringBuilder gap = new StringBuilder();
    boolean open = false;
    int i = 0;
    // Hand-written rather than a literal-matching regex: that one backtracks per character and
    // overflows the stack on this codebase's longest SQL strings and text blocks.
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '/' && source.startsWith("//", i)) {
        while (i < source.length() && source.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && source.startsWith("/*", i)) {
        i += 2;
        while (i < source.length() && !source.startsWith("*/", i)) {
          i++;
        }
        i = Math.min(source.length(), i + 2);
      } else if (c == '\'') {
        i = skipCharLiteral(source, i);
      } else if (c == '"') {
        StringBuilder literal = new StringBuilder();
        i = readStringLiteral(source, i, literal);
        if (open && !CONCATENATION.matcher(gap).matches()) {
          addIfSql(statements, current.toString());
          current.setLength(0);
        }
        current.append(literal);
        gap.setLength(0);
        open = true;
      } else {
        if (open) {
          gap.append(c);
        }
        i++;
      }
    }
    addIfSql(statements, current.toString());
    return statements;
  }

  /** Advances past the literal starting at {@code start}, appending its content to {@code into}. */
  private static int readStringLiteral(String source, int start, StringBuilder into) {
    int i = start;
    if (source.startsWith("\"\"\"", i)) {
      i += 3;
      while (i < source.length() && !source.startsWith("\"\"\"", i)) {
        into.append(source.charAt(i++));
      }
      return Math.min(source.length(), i + 3);
    }
    i++;
    while (i < source.length() && source.charAt(i) != '"' && source.charAt(i) != '\n') {
      if (source.charAt(i) == '\\' && i + 1 < source.length()) {
        i++;
      }
      into.append(source.charAt(i++));
    }
    return Math.min(source.length(), i + 1);
  }

  private static int skipCharLiteral(String source, int start) {
    int i = start + 1;
    while (i < source.length() && source.charAt(i) != '\'' && source.charAt(i) != '\n') {
      if (source.charAt(i) == '\\' && i + 1 < source.length()) {
        i++;
      }
      i++;
    }
    return Math.min(source.length(), i + 1);
  }

  private static void addIfSql(List<String> statements, String candidate) {
    if (!candidate.isEmpty() && SQL_VERB.matcher(candidate).find()) {
      statements.add(candidate);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
