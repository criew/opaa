package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link AssetType} and the database check it is mirrored by must accept the same values. The two
 * live in different languages and different files, so only a test holds them together: a value the
 * constructor lets through but the column rejects surfaces as a failed write in production, and one
 * the column lets through but the converter rejects takes down every entity read of the table.
 *
 * <p>Reads the regexes out of the baseline rather than restating them here - a copy in the test
 * would drift with the same silence as the copy it is meant to catch. Length is deliberately not
 * compared: the column's own {@code varchar(30)} holds that half, and {@link AssetType} refuses it
 * one layer earlier.
 */
class AssetTypeFormatParityTest {

  private static final Path BASELINE =
      Path.of("src/main/resources/db/changelog/rights/2026-09-27-baseline.yaml");

  /** Every table that names an asset by type and id, each with its own format check. */
  private static final Set<String> TYPE_INDEPENDENT_TABLES =
      Set.of(
          "assets",
          "asset_grants",
          "asset_grant_history",
          "asset_ownership_history",
          "asset_visibility_history",
          "permission_transfer_objects");

  /** {@code CONSTRAINT chk_<table>_asset_type_format CHECK (...)} as the baseline writes it. */
  private static final Pattern FORMAT_CHECK =
      Pattern.compile(
          "CONSTRAINT chk_(\\w+)_asset_type_format CHECK \\(\\(\\(asset_type\\)::text ~"
              + " '([^']+)'::text\\)\\)");

  /**
   * Probes on both sides of every rule either expression states: case, first character, allowed
   * characters, anchoring, and the empty value.
   */
  private static final List<String> PROBES =
      List.of(
          "KNOWLEDGE_LIBRARY",
          "PROMPT_LIBRARY",
          "AGENT",
          "A",
          "A1",
          "A_1_B",
          "",
          "knowledge_library",
          "Knowledge_Library",
          "1LIBRARY",
          "_LIBRARY",
          "PROMPT LIBRARY",
          "PROMPT-LIBRARY",
          "PROMPT.LIBRARY",
          "PROMPT\nLIBRARY",
          "PROMPT_LIBRARY\nfoo",
          "foo\nPROMPT_LIBRARY",
          "PROMPT_LIBRARY\n");

  @Test
  void everyTypeIndependentTableStatesTheSameFormat() {
    Map<String, String> expressions = formatExpressions();

    assertThat(expressions.keySet()).containsExactlyInAnyOrderElementsOf(TYPE_INDEPENDENT_TABLES);
    assertThat(new LinkedHashSet<>(expressions.values()))
        .as("the shell, the grants and every history must accept the same asset types")
        .hasSize(1);
  }

  @Test
  void theDatabaseCheckAcceptsExactlyWhatAssetTypeAccepts() {
    Pattern databaseCheck = asPostgresWouldApplyIt(formatExpression());

    for (String probe : PROBES) {
      boolean acceptedByDatabase = databaseCheck.matcher(probe).find();
      boolean acceptedByJava = catchThrowable(() -> AssetType.of(probe)) == null;

      assertThat(acceptedByJava)
          .as("AssetType and %s must agree on %s", databaseCheck.pattern(), quoted(probe))
          .isEqualTo(acceptedByDatabase);
    }
  }

  /**
   * Postgres' {@code ~} searches rather than matches, so an unanchored expression would accept a
   * value with a newline in it - which {@link AssetType} refuses. Both anchors are therefore part
   * of the contract, not decoration.
   */
  @Test
  void theDatabaseCheckIsAnchoredAtBothEnds() {
    String expression = formatExpression();

    assertThat(expression).startsWith("^").endsWith("$");
  }

  /**
   * Java's {@code $} also matches before a trailing line terminator, Postgres' does not - without
   * the translation this model would call a value with a trailing newline acceptable to a column
   * that in fact rejects it, and the parity would look broken where it is not.
   */
  private static Pattern asPostgresWouldApplyIt(String expression) {
    assertThat(expression).startsWith("^").endsWith("$");
    return Pattern.compile("\\A" + expression.substring(1, expression.length() - 1) + "\\z");
  }

  /** The format expression per table, as the baseline states it. */
  private static Map<String, String> formatExpressions() {
    Map<String, String> expressions = new LinkedHashMap<>();
    Matcher check = FORMAT_CHECK.matcher(read(BASELINE));
    while (check.find()) {
      expressions.put(check.group(1), check.group(2));
    }
    return expressions;
  }

  private static String formatExpression() {
    String expression = formatExpressions().get("asset_grants");
    assertThat(expression).as("asset_grants must carry a format check on asset_type").isNotNull();
    return expression;
  }

  private static String quoted(String probe) {
    return "\"" + probe.replace("\n", "\\n") + "\"";
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
