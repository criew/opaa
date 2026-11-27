package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The deterministic judgement of an answer to a case of the answer-quality domain: no model
 * decides. An answer is read for a refusal phrase ("not in the sources"), an unneeded premise or
 * gap note, the expected value and a wrong reference. The patterns are a sample of how German
 * answers phrase these things, not a complete grammar; a case an answer words in a way no pattern
 * knows counts as clean on that axis, so the rates are lower bounds.
 */
final class AnswerQualityAnswers {

  private AnswerQualityAnswers() {}

  private static final Pattern CITATION_MARKER = Pattern.compile("【[^】]*】");

  /** The answer claims the information is missing from the sources. */
  static final List<Pattern> REFUSAL =
      compile(
          "nicht\\s+(\\w+\\s+){0,4}in\\s+(den|der|dem)\\s+(\\w+\\s+){0,3}(Quellen|Dokumenten|Unterlagen|Kontext|Informationen)",
          "(keine|nicht\\s+\\w+)\\s+(\\w+\\s+){0,3}(Informationen|Angaben|Hinweise|Treffer)\\s+(\\w+\\s+){0,4}(gefunden|enthalten|vorhanden|verfügbar|zu finden)",
          "kann\\s+(ich\\s+)?(\\w+\\s+){0,3}nicht\\s+beantworten",
          "nicht\\s+beantwortet\\s+werden",
          "(ist|sind)\\s+(\\w+\\s+){0,3}nicht\\s+(\\w+\\s+){0,3}(zu\\s+finden|enthalten|aufgeführt|genannt)",
          "keine\\s+(\\w+\\s+){0,3}(Informationen|Angaben)\\s+(zu|über|dazu)");

  /**
   * Phrases that claim missing information; they count as a refusal only in an answer that lacks
   * the expected value, because a correct answer may name a gap of something else.
   */
  static final List<Pattern> MISSING_CLAIM =
      compile(
          "keine\\s+(\\w+\\s+){0,2}(Informationen|Angaben|Auskünfte|Details)",
          "(nicht|nirgends)\\s+(\\w+\\s+){0,3}(gefunden|auffindbar|enthalten|vorhanden|verfügbar)");

  /** A note on a premise or a gap that a fully answerable question does not need. */
  static final List<Pattern> CAVEAT =
      compile(
          "\\bhinweis\\s*:",
          "\\bprämisse",
          "(die|ihre)\\s+frage\\s+(setzt|geht)",
          "geht\\s+(\\w+\\s+){0,2}davon\\s+aus",
          "(nicht|kein\\w*)\\s+(\\w+\\s+){0,3}(ausdrücklich|explizit)\\s+(genannt|erwähnt|geregelt|angegeben)",
          "(leider|allerdings|jedoch)[^.]{0,80}(keine|nicht)\\s+(\\w+\\s+){0,3}(Angabe|Information|Aussage|Hinweis)",
          "(Quellen|Dokumente|Unterlagen)\\s+(enthalten|nennen|machen)\\s+(\\w+\\s+){0,3}(keine|nicht)",
          "(es\\s+)?(ist|bleibt)\\s+(\\w+\\s+){0,3}unklar");

  private static List<Pattern> compile(String... regexes) {
    List<Pattern> patterns = new ArrayList<>();
    for (String regex : regexes) {
      patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }
    return List.copyOf(patterns);
  }

  /**
   * What an answer shows. {@code valueMissing}: a required pattern does not match; {@code
   * wrongReference}: a forbidden pattern matches, or a required one is missing in a case of the
   * class wrong reference.
   */
  record Verdict(boolean refusal, boolean caveat, boolean valueMissing, boolean wrongReference) {

    /** Whether the answer is what the case's class demands. */
    boolean passed(String klass) {
      return switch (klass) {
        case AnswerQualityCase.FALSE_NEGATIVE -> !refusal && !valueMissing;
        case AnswerQualityCase.NEEDLESS_CAVEAT -> !refusal && !caveat && !valueMissing;
        case AnswerQualityCase.WRONG_REFERENCE -> !refusal && !wrongReference;
        default -> throw new IllegalStateException("Unknown class: " + klass);
      };
    }
  }

  static Verdict judge(AnswerQualityCase c, String answer) {
    String text = clean(answer);
    boolean caveat = CAVEAT.stream().anyMatch(p -> p.matcher(text).find());
    boolean valueMissing =
        c.answerMustMatch().stream()
            .anyMatch(
                regex -> !Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find());
    boolean forbidden =
        c.answerMustNotMatch().stream()
            .anyMatch(
                regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find());
    boolean refusal =
        REFUSAL.stream().anyMatch(p -> p.matcher(text).find())
            || (valueMissing && MISSING_CLAIM.stream().anyMatch(p -> p.matcher(text).find()));
    boolean wrongReference =
        forbidden || (AnswerQualityCase.WRONG_REFERENCE.equals(c.klass()) && valueMissing);
    return new Verdict(refusal, caveat, valueMissing, wrongReference);
  }

  /** The answer without citation markers (their ids carry digits) and with plain blanks. */
  static String clean(String answer) {
    if (answer == null) {
      return "";
    }
    return CITATION_MARKER
        .matcher(answer)
        .replaceAll(" ")
        .replace(' ', ' ')
        .replace(' ', ' ')
        .replaceAll("\\s+", " ")
        .strip();
  }

  /** One answered case. {@code answer} is {@code null} when the run did not ask the model. */
  record Result(AnswerQualityCase testCase, String answer, Verdict verdict) {
    boolean passed() {
      return verdict.passed(testCase.klass());
    }
  }

  /** Per class: cases, passed cases and how often each failure axis shows. */
  record ClassSummary(
      int cases, int passed, int refusals, int caveats, int wrongReferences, int valuesMissing) {

    double failureRate() {
      return cases == 0 ? 0.0 : (double) (cases - passed) / cases;
    }
  }

  /** Asks {@code answerer} each case's question and judges the answer. */
  static List<Result> run(
      List<AnswerQualityCase> cases, Function<AnswerQualityCase, String> answerer) {
    List<Result> results = new ArrayList<>();
    for (AnswerQualityCase c : cases) {
      String answer = answerer.apply(c);
      results.add(new Result(c, answer, judge(c, answer)));
    }
    return List.copyOf(results);
  }

  static Map<String, ClassSummary> summarize(List<Result> results) {
    Map<String, ClassSummary> summaries = new LinkedHashMap<>();
    for (String klass : AnswerQualityCase.CLASSES) {
      List<Result> members =
          results.stream().filter(r -> r.testCase().klass().equals(klass)).toList();
      summaries.put(
          klass,
          new ClassSummary(
              members.size(),
              (int) members.stream().filter(Result::passed).count(),
              (int) members.stream().filter(r -> r.verdict().refusal()).count(),
              (int) members.stream().filter(r -> r.verdict().caveat()).count(),
              (int) members.stream().filter(r -> r.verdict().wrongReference()).count(),
              (int) members.stream().filter(r -> r.verdict().valueMissing()).count()));
    }
    return summaries;
  }
}
