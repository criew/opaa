package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One case of {@code eval/golden/answer-quality.json}: a question that the corpus answers fully,
 * the evidence that must reach the model together, and the deterministic checks an answer must
 * pass. Every check is a text or pattern check, never a model verdict
 * (eval/corpus/answer-quality/SOURCE.md).
 *
 * @param klass one of {@link #CLASSES}
 * @param evidence strings that must all stand in <em>one</em> passage of the prompt context; the
 *     answer value together with its unit, or with its row and column
 * @param answerMustMatch regular expressions that must all match the answer
 * @param answerMustNotMatch regular expressions of a wrong reference; none may match
 * @param questionVariants further spellings of the question (identifier forms), each of which must
 *     bring the document into the context
 * @param canonicalAnswer a detailed, correct answer that also names neighbouring figures; it must
 *     pass every check of its case
 * @param wrongAnswer for the class wrong reference an answer with the figure on the wrong
 *     reference; a forbidden pattern must match it; {@code null} in the other classes
 * @param expectedState declared state per measurement layer ({@link #LAYER_FORMAT}, {@link
 *     #LAYER_CONTEXT})
 */
record AnswerQualityCase(
    String id,
    String klass,
    String question,
    List<String> documents,
    List<String> evidence,
    List<String> answerMustMatch,
    List<String> answerMustNotMatch,
    List<String> questionVariants,
    List<String> tags,
    String canonicalAnswer,
    String wrongAnswer,
    Map<String, ExpectedState> expectedState) {

  static final String FALSE_NEGATIVE = "false_negative";
  static final String NEEDLESS_CAVEAT = "needless_caveat";
  static final String WRONG_REFERENCE = "wrong_reference";
  static final List<String> CLASSES = List.of(FALSE_NEGATIVE, NEEDLESS_CAVEAT, WRONG_REFERENCE);

  /** The Docker-free layer: what the production formats cut out of the files. */
  static final String LAYER_FORMAT = "format";

  /** The layer through embedding, search and neighbour expansion up to the prompt context. */
  static final String LAYER_CONTEXT = "context";

  static final String SOLVED = "solved";
  static final String KNOWN_GAP = "known_gap";
  static final String UNMEASURED = "unmeasured";

  /** {@code state} is {@link #SOLVED}, {@link #KNOWN_GAP} or {@link #UNMEASURED}. */
  record ExpectedState(String state, String since, String reason) {}

  /**
   * A table document whose later chunks must repeat the header: the chunk of the last data row
   * carries every header cell.
   */
  record TableCheck(String document, List<String> headerCells, String lastRow) {}

  record Dataset(List<AnswerQualityCase> cases, List<TableCheck> tableChecks) {}

  /** Every spelling of the question, the canonical one first. */
  List<String> allQuestions() {
    List<String> all = new ArrayList<>();
    all.add(question);
    all.addAll(questionVariants);
    return all;
  }

  static Dataset load(Path file) throws IOException {
    JsonNode root =
        JsonMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
    List<AnswerQualityCase> cases = new ArrayList<>();
    for (JsonNode node : root.get("cases")) {
      Map<String, ExpectedState> states = new LinkedHashMap<>();
      if (node.hasNonNull("expected_state")) {
        node.get("expected_state")
            .properties()
            .forEach(
                entry ->
                    states.put(
                        entry.getKey(),
                        new ExpectedState(
                            entry.getValue().get("state").asString(),
                            entry.getValue().get("since").asString(),
                            entry.getValue().get("reason").asString())));
      }
      cases.add(
          new AnswerQualityCase(
              node.get("id").asString(),
              node.get("class").asString(),
              node.get("question").asString(),
              strings(node.get("documents")),
              strings(node.get("evidence")),
              strings(node.get("answer_must_match")),
              strings(node.get("answer_must_not_match")),
              node.hasNonNull("question_variants")
                  ? strings(node.get("question_variants"))
                  : List.of(),
              strings(node.get("tags")),
              node.get("canonical_answer").asString(),
              node.hasNonNull("wrong_answer") ? node.get("wrong_answer").asString() : null,
              Map.copyOf(states)));
    }
    List<TableCheck> checks = new ArrayList<>();
    for (JsonNode node : root.get("table_checks")) {
      checks.add(
          new TableCheck(
              node.get("document").asString(),
              strings(node.get("header_cells")),
              node.get("last_row").asString()));
    }
    return new Dataset(List.copyOf(cases), List.copyOf(checks));
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asString()));
    return List.copyOf(values);
  }

  /** Lower-cased text with every run of whitespace, also no-break, as one blank. */
  static String normalize(String text) {
    return text.replace(' ', ' ').replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether one of {@code passages} holds every evidence string. Evidence spread over two passages
   * does not count: the model reads each passage with its own head, and a number in one passage
   * with its unit or column in another is what the class "wrong reference" is made of.
   */
  static boolean evidenceInOnePassage(List<String> passages, List<String> evidence) {
    List<String> needles = evidence.stream().map(AnswerQualityCase::normalize).toList();
    return passages.stream()
        .map(AnswerQualityCase::normalize)
        .anyMatch(passage -> needles.stream().allMatch(passage::contains));
  }
}
