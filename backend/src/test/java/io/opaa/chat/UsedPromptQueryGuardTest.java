package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The source side of "no evaluation of prompt usage": every report by prompt or by person across
 * conversations would have to read {@code chat_messages.used_prompt_id}, so nothing but the entity
 * mapping may name the column or its field in a query. Holds what {@code
 * io.opaa.api.PromptUsageSpecificationTest} cannot see: an endpoint under another name. A write
 * through the entity needs no query; a read of one chat goes through {@link ChatMessage} as a
 * whole.
 */
class UsedPromptQueryGuardTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
  private static final Path CHANGELOGS = Path.of("src", "main", "resources", "db", "changelog");
  private static final Path ENTITY = MAIN_SOURCES.resolve("io/opaa/chat/ChatMessage.java");
  private static final String BASELINE = "001-baseline.yaml";

  @Test
  void noQueryStringNamesTheUsedPromptOutsideTheEntityMapping() {
    List<String> offenses = new ArrayList<>();
    for (Path file : javaFiles()) {
      if (file.equals(ENTITY)) {
        continue;
      }
      for (String literal : stringLiterals(read(file))) {
        if (mentionsUsedPrompt(literal)) {
          offenses.add(file + ": " + literal);
        }
      }
    }

    assertThat(offenses)
        .as("no JPQL, native SQL or JdbcTemplate statement reads the used prompt")
        .isEmpty();
  }

  @Test
  void noRepositoryDerivesAQueryFromTheUsedPrompt() {
    List<String> offenses =
        javaFiles().stream()
            .filter(file -> file.getFileName().toString().endsWith("Repository.java"))
            .filter(file -> mentionsUsedPrompt(read(file)))
            .map(Path::toString)
            .toList();

    assertThat(offenses)
        .as("a derived query such as findByUsedPromptId would be the evaluation by another name")
        .isEmpty();
  }

  /**
   * The columns and their pairing check are part of the {@code chat_messages} table definition in
   * the baseline; no other statement there, and no later changeset, names them.
   */
  @Test
  void onlyTheTableDefinitionTouchesTheColumns() throws IOException {
    List<String> offenses = new ArrayList<>();
    try (Stream<Path> files = Files.walk(CHANGELOGS)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (!file.getFileName().toString().equals(BASELINE)) {
          if (mentionsUsedPrompt(read(file))) {
            offenses.add(file.toString());
          }
          continue;
        }
        for (String statement : read(file).split(";\\s*\\n")) {
          if (mentionsUsedPrompt(statement)
              && !statement.strip().startsWith("CREATE TABLE chat_messages (")) {
            offenses.add(file + ": " + statement.strip());
          }
        }
      }
    }

    assertThat(offenses).as("no index, view or function over the used prompt").isEmpty();
  }

  /** Guards the premise of the statement filter above: the table definition carries the columns. */
  @Test
  void theBaselineDefinesTheColumnsInTheChatMessagesTable() {
    String baseline = read(CHANGELOGS.resolve("changes").resolve(BASELINE));
    String table =
        baseline.substring(baseline.indexOf("CREATE TABLE chat_messages (")).split(";\\s*\\n")[0];

    assertThat(table).contains("used_prompt_id uuid", "chk_chat_messages_used_prompt");
  }

  /** Guards the premise: the scan would pass just as well against an empty tree. */
  @Test
  void theEntityMappingIsActuallyScannedAndNamesTheColumns() {
    assertThat(javaFiles()).contains(ENTITY);
    assertThat(read(ENTITY)).contains("\"used_prompt_id\"", "\"used_prompt_title\"");
  }

  /** The scan finds a query in code and ignores the same words in a comment. */
  @Test
  void theScanSeesAQueryButNotAComment() {
    String source =
        "// \"used_prompt\" in a comment\n"
            + "@Query(\"select m from ChatMessage m where m.usedPromptId = :id\")\n"
            + "char quote = '\"';\n";

    assertThat(stringLiterals(source))
        .filteredOn(UsedPromptQueryGuardTest::mentionsUsedPrompt)
        .containsExactly("\"select m from ChatMessage m where m.usedPromptId = :id\"");
  }

  /**
   * The string literals and text blocks of a Java source, comments and character literals skipped -
   * a quote inside a comment must not shift what counts as a literal.
   */
  static List<String> stringLiterals(String source) {
    List<String> literals = new ArrayList<>();
    int i = 0;
    int length = source.length();
    while (i < length) {
      char c = source.charAt(i);
      if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        i = end < 0 ? length : end;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? length : end + 2;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        int stop = end < 0 ? length : end + 3;
        literals.add(source.substring(i, stop));
        i = stop;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < length && source.charAt(j) != c) {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        if (c == '"') {
          literals.add(source.substring(i, Math.min(j + 1, length)));
        }
        i = j + 1;
      } else {
        i++;
      }
    }
    return literals;
  }

  private static boolean mentionsUsedPrompt(String text) {
    String normalized = text.toLowerCase(Locale.ROOT);
    return normalized.contains("used_prompt") || normalized.contains("usedprompt");
  }

  private static List<Path> javaFiles() {
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      return files.filter(file -> file.toString().endsWith(".java")).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
