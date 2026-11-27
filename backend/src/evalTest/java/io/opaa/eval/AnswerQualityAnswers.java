package io.opaa.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

  private static final int FLAGS =
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

  private static final Pattern CITATION_MARKER = Pattern.compile("【[^】]*】");

  /** A thousands group written with a point or a blank: {@code 1.000}, {@code 1 000}. */
  private static final Pattern THOUSANDS =
      Pattern.compile("(?<![\\d.,])\\d{1,3}(?:[. ]\\d{3})+(?!\\d)");

  private static final Pattern ZERO_DECIMALS = Pattern.compile("(\\d),00(?!\\d)");
  private static final Pattern CURRENCY = Pattern.compile("€|\\bEUR\\b");
  private static final Pattern PERCENT = Pattern.compile("\\s*%");
  private static final Pattern MIDNIGHT =
      Pattern.compile("Mitternacht|24[:.]00\\s*Uhr|\\b0(?:[:.]00)?\\s*Uhr", FLAGS);
  private static final Pattern DASH_DECIMALS = Pattern.compile("(\\d),-");
  private static final Pattern CENT = Pattern.compile("\\b(\\d{1,2})\\s*Cent\\b", FLAGS);
  private static final Pattern PER_HUNDRED =
      Pattern.compile("\\s*(v\\.\\s*H\\.|vom\\s+Hundert)", FLAGS);
  private static final Pattern NUMBER_WORD =
      Pattern.compile("\\b(zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)\\b", FLAGS);
  private static final List<String> NUMBER_WORDS =
      List.of(
          "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf",
          "zwölf");

  private static final Map<String, Pattern> CASE_PATTERNS = new ConcurrentHashMap<>();

  /**
   * Phrases that say a piece of information is missing, whether they name the sources ("steht nicht
   * in den Unterlagen") or not ("keine Angaben"). A correct answer may use them for something else
   * ("Ob sie an Feiertagen entfällt, steht nicht in den Unterlagen"), so they count as a refusal
   * only in an answer that lacks the expected value.
   */
  static final List<Pattern> MISSING_CLAIM =
      compile(
          "nicht\\s+(\\w+\\s+){0,4}in\\s+(den|der|dem)\\s+(\\w+\\s+){0,3}(Quellen|Dokumenten|Unterlagen|Kontextdokumenten)",
          "kann\\s+(ich\\s+)?(\\w+\\s+){0,3}nicht\\s+beantworten",
          "nicht\\s+beantwortet\\s+werden",
          "keine\\s+(\\w+\\s+){0,2}(Informationen?|Angaben?|Auskünfte|Details)",
          "geht\\s+(\\w+\\s+){0,4}nicht\\s+hervor",
          "nichts\\s+(\\w+\\s+){0,2}gefunden",
          "(nicht|nirgends)\\s+(\\w+\\s+){0,3}(gefunden|auffindbar|enthalten|vorhanden|verfügbar|aufgeführt|genannt)",
          "(Quellen|Unterlagen|Dokumenten)\\s+(\\w+\\s+){0,5}(keine|nichts)");

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
      patterns.add(Pattern.compile(regex, FLAGS));
    }
    return List.copyOf(patterns);
  }

  private static boolean matches(String regex, String text) {
    return CASE_PATTERNS
        .computeIfAbsent(regex, r -> Pattern.compile(r, FLAGS))
        .matcher(text)
        .find();
  }

  private static boolean anyMatches(List<Pattern> patterns, String text) {
    return patterns.stream().anyMatch(p -> p.matcher(text).find());
  }

  /**
   * What an answer shows. {@code valueMissing}: a required pattern does not match; {@code
   * forbiddenHit}: a forbidden pattern matches; {@code wrongReference}: that, or a required pattern
   * missing in a case of the class wrong reference.
   */
  record Verdict(
      boolean refusal,
      boolean caveat,
      boolean valueMissing,
      boolean forbiddenHit,
      boolean wrongReference) {

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
    boolean valueMissing = c.answerMustMatch().stream().anyMatch(regex -> !matches(regex, text));
    boolean forbidden = c.answerMustNotMatch().stream().anyMatch(regex -> matches(regex, text));
    boolean refusal = valueMissing && anyMatches(MISSING_CLAIM, text);
    boolean wrongReference =
        forbidden || (AnswerQualityCase.WRONG_REFERENCE.equals(c.klass()) && valueMissing);
    return new Verdict(refusal, anyMatches(CAVEAT, text), valueMissing, forbidden, wrongReference);
  }

  /**
   * The answer as the patterns read it: without citation markers (their ids carry digits), with
   * plain blanks, thousands separators and zero decimals removed ({@code 1.000,00 Euro} reads
   * {@code 1000 Euro}), {@code €} and {@code EUR} as {@code Euro}, {@code %} as {@code Prozent},
   * midnight and 0 Uhr as {@code 24 Uhr}, {@code 1000,-} as {@code 1000}, {@code 30 Cent} as {@code
   * 0,30 Euro}, {@code v. H.} as percent, number words from two to twelve as digits ("ein" stays:
   * it is mostly an article).
   */
  static String clean(String answer) {
    if (answer == null) {
      return "";
    }
    String text =
        CITATION_MARKER.matcher(answer).replaceAll(" ").replace(' ', ' ').replace(' ', ' ');
    text = THOUSANDS.matcher(text).replaceAll(m -> m.group().replaceAll("[. ]", ""));
    text = DASH_DECIMALS.matcher(text).replaceAll("$1");
    text = ZERO_DECIMALS.matcher(text).replaceAll("$1");
    text =
        CENT.matcher(text)
            .replaceAll(m -> String.format("0,%02d Euro", Integer.parseInt(m.group(1))));
    text = PER_HUNDRED.matcher(text).replaceAll(" Prozent");
    text =
        NUMBER_WORD
            .matcher(text)
            .replaceAll(
                m ->
                    Integer.toString(
                        NUMBER_WORDS.indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 2));
    text = CURRENCY.matcher(text).replaceAll(" Euro");
    text = PERCENT.matcher(text).replaceAll(" Prozent");
    text = MIDNIGHT.matcher(text).replaceAll("24 Uhr");
    return text.replaceAll("\\s+", " ").strip();
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
