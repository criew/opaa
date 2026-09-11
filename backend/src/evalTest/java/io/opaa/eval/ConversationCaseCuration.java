package io.opaa.eval;

import io.opaa.eval.GoldenCaseCuration.Violation;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The curation rules a multi-turn dataset must satisfy (issue #1484,
 * docs/features/conversation-memory.md, "Messung") - checked Docker-free, so a hand-edited dataset
 * fails in {@code evalUnitTest} rather than an hour into a Testcontainers run. The single-question
 * counterpart is {@link GoldenCaseCuration}, whose {@link Violation} type is reused so both
 * datasets report a finding in the same vocabulary.
 *
 * <ul>
 *   <li><b>Every turn carries {@code query}, {@code answer} and {@code expected_documents}.</b> A
 *       turn without an answer has no assistant half and would hand the next turn a window
 *       production never produces; a turn without expected documents is not measured by anything.
 *   <li><b>The class is one of {@link #CASE_CLASSES}</b>, and its turn count lies in the range that
 *       class is defined over ({@link #TURN_RANGE_BY_CLASS}) - a two-turn {@code
 *       constraint_carryover} case cannot contain the intermediate turn that pushes the constraint
 *       out of the search window, which is the whole point of that class.
 *   <li><b>The state fields are mandatory</b>, with the same shape and the same reasoning as in
 *       {@link GoldenCaseCuration}: a state that may be left empty is a state nobody can tell a
 *       known gap from a regression by. {@code expected_state_exception} keeps its meaning from
 *       there - an optional, written reason why a <b>single</b> case deviates from its declared
 *       state on purpose, only on a {@code known_gap} case. The single-pathedness of this
 *       measurement is a property of the dataset, not of a case, and is recorded once per report
 *       ({@link ConversationEvaluationReport#SINGLE_PATH_NOTE}).
 *   <li><b>A {@code constraint_carryover} case names a {@link #CONFUSABLE_DOCUMENT_RULE confusable
 *       document}</b> in at least one turn; a {@code topic_switch} case names its {@link
 *       #TOPIC_SWITCH_TURN_RULE change turn}.
 *   <li><b>Class sizes</b>: at least {@link #MINIMUM_CASES_PER_CLASS} cases and, across their
 *       turns, at least {@link GoldenCaseCuration#MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS}
 *       distinct expected-document sets - the tolerance of ADR-0013 is computed from the latter,
 *       not from the case count.
 * </ul>
 */
public final class ConversationCaseCuration {

  /** docs/features/conversation-memory.md, "Fallklassen". */
  public static final List<String> CASE_CLASSES =
      List.of("anaphora_resolution", "topic_switch", "constraint_carryover");

  /** The class whose cases measure a constraint carried across an intermediate turn. */
  public static final String CONSTRAINT_CARRYOVER_CLASS = "constraint_carryover";

  /** The class whose cases measure that a topic change stops colouring the search. */
  public static final String TOPIC_SWITCH_CLASS = "topic_switch";

  /** Unchanged from {@link GoldenCaseCuration#MINIMUM_CASES_PER_CLASS} - the same rule. */
  public static final int MINIMUM_CASES_PER_CLASS = GoldenCaseCuration.MINIMUM_CASES_PER_CLASS;

  /** The turn count each class is defined over (docs/features/conversation-memory.md, table). */
  public static final Map<String, int[]> TURN_RANGE_BY_CLASS =
      Map.of(
          "anaphora_resolution", new int[] {2, 3},
          "topic_switch", new int[] {3, 4},
          "constraint_carryover", new int[] {3, 5});

  /**
   * Upper bound of a hand-written short answer. "One to three sentences" is the curation
   * instruction; this is the mechanical bound behind it, generous enough for three long German
   * sentences and tight enough that a pasted document section fails here instead of quietly
   * becoming the largest part of the conversation window.
   */
  public static final int MAXIMUM_ANSWER_LENGTH = 400;

  public static final String CONFUSABLE_DOCUMENT_RULE =
      "a constraint_carryover case names a confusable_document in at least one turn - without the "
          + "document the carried constraint exists to keep out, the class measures nothing";

  /**
   * The turn a {@code topic_switch} case changes its topic in, 1-based; every earlier turn belongs
   * to the previous topic. Named in the dataset rather than derived from the expected documents,
   * exactly as docs/features/conversation-memory.md prescribes ("Es gibt keine Erkennung"): a
   * derivation from "shares no document with an earlier turn" would also fire on an ordinary
   * follow-up whose answer happens to sit in a different document, and would then count the correct
   * previous-topic document as bleed.
   */
  public static final String TOPIC_SWITCH_TURN_RULE =
      "a topic_switch case names topic_switch_turn - the 1-based turn it changes topic in, at least "
          + "2 and at most its turn count; no other class carries the field";

  private ConversationCaseCuration() {}

  /**
   * Validates a whole dataset, returning every violation rather than throwing on the first - a
   * curation round wants the full list.
   *
   * @param domain the domain every case must declare, matching {@link EvalDomainConfig#name()}.
   * @param corpusFileNames the manifest's file list; every expected and confusable document must
   *     exist in it.
   */
  public static List<Violation> validate(
      List<ConversationCase> cases, String domain, Set<String> corpusFileNames) {
    List<Violation> violations = new ArrayList<>();
    Set<String> seenIds = new LinkedHashSet<>();

    for (ConversationCase conversationCase : cases) {
      String id = conversationCase.id();
      if (!seenIds.add(id)) {
        violations.add(new Violation(id, "duplicate id"));
      }
      if (!domain.equals(conversationCase.domain())) {
        violations.add(
            new Violation(
                id, "domain is '" + conversationCase.domain() + "', expected '" + domain + "'"));
      }
      validateClassAndTurnCount(conversationCase, violations);
      validateTurns(conversationCase, corpusFileNames, violations);
      validateTopicSwitchTurn(conversationCase, violations);
      validateState(conversationCase, violations);
    }

    validateClassSizes(cases, violations);
    return List.copyOf(violations);
  }

  private static void validateClassAndTurnCount(
      ConversationCase conversationCase, List<Violation> violations) {
    String id = conversationCase.id();
    String category = conversationCase.category();
    if (category == null || !CASE_CLASSES.contains(category)) {
      violations.add(
          new Violation(id, "category '" + category + "' is not one of " + CASE_CLASSES));
      return;
    }
    List<ConversationCase.Turn> turns = conversationCase.turns();
    int turnCount = turns == null ? 0 : turns.size();
    int[] range = TURN_RANGE_BY_CLASS.get(category);
    if (turnCount < range[0] || turnCount > range[1]) {
      violations.add(
          new Violation(
              id,
              "class '"
                  + category
                  + "' is defined over "
                  + range[0]
                  + " to "
                  + range[1]
                  + " turns, this case has "
                  + turnCount));
    }
  }

  private static void validateTurns(
      ConversationCase conversationCase, Set<String> corpusFileNames, List<Violation> violations) {
    String id = conversationCase.id();
    List<ConversationCase.Turn> turns = conversationCase.turns();
    if (turns == null || turns.isEmpty()) {
      violations.add(new Violation(id, "turns must not be empty"));
      return;
    }
    Set<String> seenQueries = new LinkedHashSet<>();
    boolean anyConfusable = false;
    for (int i = 0; i < turns.size(); i++) {
      ConversationCase.Turn turn = turns.get(i);
      String turnId = conversationCase.turnId(i);
      if (turn.query() == null || turn.query().isBlank()) {
        violations.add(new Violation(turnId, "query is missing or blank"));
      } else if (!seenQueries.add(turn.query())) {
        violations.add(new Violation(turnId, "duplicate query within the same case"));
      }
      if (turn.answer() == null || turn.answer().isBlank()) {
        violations.add(
            new Violation(
                turnId,
                "answer is missing or blank - without it the next turn receives a conversation "
                    + "window production never produces"));
      } else if (turn.answer().length() > MAXIMUM_ANSWER_LENGTH) {
        violations.add(
            new Violation(
                turnId,
                "answer is "
                    + turn.answer().length()
                    + " characters, more than the short-answer bound of "
                    + MAXIMUM_ANSWER_LENGTH));
      }
      validateExpectedDocuments(turn, turnId, corpusFileNames, violations);
      anyConfusable |= validateConfusable(turn, turnId, corpusFileNames, violations);
    }
    if (CONSTRAINT_CARRYOVER_CLASS.equals(conversationCase.category()) && !anyConfusable) {
      violations.add(new Violation(id, CONFUSABLE_DOCUMENT_RULE));
    }
  }

  private static void validateExpectedDocuments(
      ConversationCase.Turn turn,
      String turnId,
      Set<String> corpusFileNames,
      List<Violation> violations) {
    List<String> expected = turn.expectedDocuments();
    if (expected == null || expected.isEmpty()) {
      violations.add(new Violation(turnId, "expected_documents must not be empty"));
      return;
    }
    if (expected.size() > GoldenCaseCuration.MAXIMUM_EXPECTED_DOCUMENTS) {
      violations.add(
          new Violation(
              turnId,
              "expected_documents has "
                  + expected.size()
                  + " entries, more than the curation window's upper bound of "
                  + GoldenCaseCuration.MAXIMUM_EXPECTED_DOCUMENTS));
    }
    if (new LinkedHashSet<>(expected).size() != expected.size()) {
      violations.add(new Violation(turnId, "expected_documents contains duplicates"));
    }
    for (String fileName : expected) {
      if (!corpusFileNames.contains(fileName)) {
        violations.add(
            new Violation(turnId, "expected document '" + fileName + "' is not in the corpus"));
      }
    }
  }

  /**
   * @return whether this turn declares a (valid or invalid) confusable document.
   */
  private static boolean validateConfusable(
      ConversationCase.Turn turn,
      String turnId,
      Set<String> corpusFileNames,
      List<Violation> violations) {
    String confusable = turn.confusableDocument();
    if (confusable == null) {
      return false;
    }
    if (!corpusFileNames.contains(confusable)) {
      violations.add(
          new Violation(turnId, "confusable_document '" + confusable + "' is not in the corpus"));
    }
    if (turn.expectedDocuments() != null && turn.expectedDocuments().contains(confusable)) {
      violations.add(new Violation(turnId, "confusable_document is itself an expected document"));
    }
    return true;
  }

  /** See {@link #TOPIC_SWITCH_TURN_RULE}: named, never derived, and only on that one class. */
  private static void validateTopicSwitchTurn(
      ConversationCase conversationCase, List<Violation> violations) {
    String id = conversationCase.id();
    Integer switchTurn = conversationCase.topicSwitchTurn();
    boolean isSwitchCase = TOPIC_SWITCH_CLASS.equals(conversationCase.category());
    if (!isSwitchCase) {
      if (switchTurn != null) {
        violations.add(
            new Violation(
                id,
                "topic_switch_turn is only defined for the topic_switch class, not for '"
                    + conversationCase.category()
                    + "'"));
      }
      return;
    }
    int turnCount = conversationCase.turns() == null ? 0 : conversationCase.turns().size();
    if (switchTurn == null) {
      violations.add(new Violation(id, TOPIC_SWITCH_TURN_RULE));
    } else if (switchTurn < 2 || switchTurn > turnCount) {
      violations.add(
          new Violation(
              id,
              "topic_switch_turn is "
                  + switchTurn
                  + ", outside [2, "
                  + turnCount
                  + "] - the first turn cannot be a change, and a turn beyond the script does not "
                  + "exist"));
    }
  }

  private static void validateState(ConversationCase conversationCase, List<Violation> violations) {
    String id = conversationCase.id();
    if (conversationCase.expectedState() == null) {
      violations.add(new Violation(id, "expected_state is missing"));
    }
    if (conversationCase.expectedStateException() != null
        && conversationCase.expectedStateException().isBlank()) {
      violations.add(new Violation(id, "expected_state_exception is present but blank"));
    }
    if (conversationCase.expectedStateException() != null
        && conversationCase.expectedState() == GoldenCase.ExpectedState.SOLVED) {
      violations.add(new Violation(id, GoldenCaseCuration.EXCEPTION_ONLY_ON_KNOWN_GAP_RULE));
    }
    if (conversationCase.expectedStateReason() == null
        || conversationCase.expectedStateReason().isBlank()) {
      violations.add(new Violation(id, "expected_state_reason is missing or blank"));
    }
    String since = conversationCase.expectedStateSince();
    if (since == null || since.isBlank()) {
      violations.add(new Violation(id, "expected_state_since is missing"));
      return;
    }
    try {
      LocalDate.parse(since);
    } catch (DateTimeParseException e) {
      violations.add(new Violation(id, "expected_state_since '" + since + "' is not an ISO date"));
    }
  }

  private static void validateClassSizes(List<ConversationCase> cases, List<Violation> violations) {
    Map<String, Integer> countsByClass = new TreeMap<>();
    Map<String, Set<List<String>>> expectedSetsByClass = new TreeMap<>();
    for (ConversationCase conversationCase : cases) {
      String category = String.valueOf(conversationCase.category());
      countsByClass.merge(category, 1, Integer::sum);
      if (conversationCase.turns() == null) {
        continue;
      }
      for (ConversationCase.Turn turn : conversationCase.turns()) {
        if (turn.expectedDocuments() != null) {
          expectedSetsByClass
              .computeIfAbsent(category, k -> new LinkedHashSet<>())
              // Sorted copy: two turns expecting the same documents in a different order are the
              // same observation, and n_eff must count them once.
              .add(turn.expectedDocuments().stream().sorted().toList());
        }
      }
    }
    for (String caseClass : CASE_CLASSES) {
      int count = countsByClass.getOrDefault(caseClass, 0);
      if (count < MINIMUM_CASES_PER_CLASS) {
        violations.add(
            new Violation(
                null,
                "case class '"
                    + caseClass
                    + "' has "
                    + count
                    + " cases, fewer than the required minimum of "
                    + MINIMUM_CASES_PER_CLASS));
      }
      int distinctSets = expectedSetsByClass.getOrDefault(caseClass, Set.of()).size();
      if (distinctSets < GoldenCaseCuration.MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS) {
        violations.add(
            new Violation(
                null,
                "case class '"
                    + caseClass
                    + "' spans only "
                    + distinctSets
                    + " distinct expected_documents sets across its turns, fewer than the required "
                    + "minimum of "
                    + GoldenCaseCuration.MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS
                    + " (see GoldenCaseCuration.MINIMUM_DISTINCT_EXPECTED_SETS_PER_CLASS)"));
      }
    }
  }
}
