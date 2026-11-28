package io.opaa.query.absence;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognises the sentences of an answer that say something is not in the sources - "in den
 * vorliegenden Dokumenten nicht enthalten", "die Quellen enthalten keine Angaben", "liegen mir
 * keine Informationen vor", "not mentioned in the provided documents". A fixed list of German and
 * English phrasings, each bound to the sources or to the answering model itself, matched without a
 * model call: the same text always gets the same verdict. "Der Bescheid muss keine Angaben
 * enthalten" or "Es liegen keine Einwände vor" state a fact of the matter and are no such claim.
 */
public final class AbsenceClaims {

  private static final String SOURCES =
      "(?:vorliegenden\\s+|bereitgestellten\\s+|verfügbaren\\s+|vorhandenen\\s+|gegebenen\\s+"
          + "|abgerufenen\\s+|genannten\\s+|übermittelten\\s+|zitierten\\s+)?"
          + "(?:quellen|dokumenten|dokumente|kontextdokumenten|kontextdokumente|unterlagen"
          + "|kontextabschnitten|abschnitten|textstellen|fundstellen|materialien|texten)";

  private static final String SOURCE =
      "(?:vorliegenden\\s+|bereitgestellten\\s+|verfügbaren\\s+|genannten\\s+|zitierten\\s+)?"
          + "(?:dokument|text|quelle|unterlage|textstelle|fundstelle)";

  /** "in den Quellen", "aus den Unterlagen", "im Dokument", "in der Quelle". */
  private static final String IN_SOURCES =
      "(?:(?:in|aus)\\s+den\\s+"
          + SOURCES
          + "|(?:im|in\\s+dem|in\\s+der|aus\\s+dem|aus\\s+der)\\s+"
          + SOURCE
          + ")\\b";

  private static final String MISSING =
      "(?:enthalten|genannt|aufgeführt|erwähnt|angegeben|vorhanden|geregelt|beschrieben"
          + "|ersichtlich|hervor|zu\\s+entnehmen|entnehmen|zu\\s+finden|gefunden|auffindbar"
          + "|festgelegt|benannt|beziffert)";

  private static final String STATEMENTS =
      "(?:angaben?|informationen|hinweise?|aussagen?|regelungen|regelung|daten|details)";

  /** "nicht enthalten", "nichts gefunden", "keine Angaben", "keine Frist genannt". */
  private static final String NOTHING =
      "(?:nicht\\s+"
          + MISSING
          + "|nichts(?:\\s+\\S+){0,2}?\\s+(?:dazu|darüber|hierzu|"
          + MISSING
          + ")|keine[nrs]?\\s+(?:"
          + STATEMENTS
          + "|\\S+\\s+"
          + MISSING
          + "))\\b";

  private static final String EN_SOURCES =
      "(?:provided\\s+|given\\s+|available\\s+|retrieved\\s+|supplied\\s+)?"
          + "(?:sources|documents|document|context|passages|material|excerpts)";

  private static final List<Pattern> PATTERNS =
      List.of(
          // "ist in den vorliegenden Dokumenten nicht enthalten", "in den Unterlagen nichts
          // gefunden", "in den Dokumenten finden sich keine Aussagen", "im Dokument nicht genannt"
          pattern("\\b" + IN_SOURCES + "(?:\\s+\\S+){0,6}?\\s+" + NOTHING),
          // "nicht in den Quellen enthalten", "keine Angaben in den Unterlagen"
          pattern("\\b(?:nicht|nichts)\\s+(?:\\S+\\s+){0,2}?" + IN_SOURCES),
          pattern(
              "\\bkeine[nrs]?\\s+(?:\\S+\\s+)?"
                  + STATEMENTS
                  + "\\s+(?:\\S+\\s+){0,2}?"
                  + IN_SOURCES),
          // "die Quellen enthalten dazu keine Angabe", "die Dokumente nennen das nicht"
          pattern(
              "\\b(?:die|das|der)\\s+(?:"
                  + SOURCES
                  + "|"
                  + SOURCE
                  + ")\\s+(?:enthalten|enthält|nennen|nennt|erwähnen|erwähnt|regeln|regelt"
                  + "|beschreiben|beschreibt|geben|gibt|liefern|liefert|sagen|sagt)"
                  + "(?:\\s+\\S+){0,6}?\\s+(?:nicht|nichts|keine[nrs]?)\\b"),
          // "findet sich hierzu keine Regelung", "finden sich keine Aussagen"
          pattern("\\b(?:findet|finden)\\s+sich\\s+(?:\\S+\\s+){0,4}?(?:keine[nrs]?|nichts)\\b"),
          // "liegen mir keine Informationen vor", "dazu liegen keine Angaben vor"
          pattern("\\bliegen\\s+(?:mir|uns)\\s+(?:\\S+\\s+){0,3}?keine[nr]?\\s+\\S+\\s+vor\\b"),
          pattern("\\bliegen\\s+(?:\\S+\\s+){0,3}?keine\\s+" + STATEMENTS + "\\s+vor\\b"),
          // "dazu gibt es keine Angabe", "habe ich keine Informationen"
          pattern(
              "\\b(?:gibt\\s+es|habe\\s+ich|finde\\s+ich|fand\\s+ich)\\s+(?:\\S+\\s+){0,3}?"
                  + "keine[nr]?\\s+(?:näheren\\s+|weiteren\\s+|konkreten\\s+|genauen\\s+)?"
                  + STATEMENTS
                  + "\\b"),
          // "lässt sich den Unterlagen nicht entnehmen", "geht aus ihnen nicht hervor"
          pattern("\\b(?:lässt|lassen)\\s+sich\\s+(?:\\S+\\s+){0,6}?nicht\\s+entnehmen\\b"),
          pattern("\\b(?:geht|gehen)\\s+(?:\\S+\\s+){0,6}?nicht\\s+hervor\\b"),
          // "konnte ich dazu nichts finden"
          pattern(
              "\\b(?:konnte|kann)\\s+ich\\s+(?:\\S+\\s+){0,4}?(?:nicht|nichts|keine[nr]?)\\b"
                  + "(?:\\s+\\S+){0,3}?\\s+finden\\b"),
          pattern(
              "\\bnot\\s+(?:\\S+\\s+){0,3}?(?:mentioned|contained|included|specified|stated"
                  + "|found|covered|provided|listed|addressed)\\s+in\\s+the\\s+"
                  + EN_SOURCES
                  + "\\b"),
          pattern(
              "\\bthe\\s+"
                  + EN_SOURCES
                  + "\\s+(?:do|does)(?:n't|\\s+not)\\s+(?:mention|contain|include|specify"
                  + "|state|provide|cover|address)\\b"),
          pattern(
              "\\b(?:there\\s+is|i\\s+have|i\\s+found|contains?|provides?)\\s+no\\s+"
                  + "(?:information|details|mention|data)\\b"),
          pattern(
              "\\b(?:i\\s+)?(?:could\\s*not|couldn't|cannot|can't|was\\s+unable\\s+to)\\s+find\\b"));

  /** A sentence ends at its punctuation, a paragraph or a list item; a soft line break does not. */
  private static final Pattern SENTENCE_END =
      Pattern.compile("(?<=[.!?:;])\\s+|\\n\\s*\\n|\\n(?=\\s*(?:[-*•]|\\d+\\.)\\s)");

  private AbsenceClaims() {}

  /** Whether {@code answer} claims, anywhere in its text, that something is not in the sources. */
  public static boolean claimsAbsence(String answer) {
    return !claimSentences(answer).isEmpty();
  }

  /** The sentences of {@code answer} that claim something is not in the sources, in order. */
  public static List<String> claimSentences(String answer) {
    return sentences(answer).stream().filter(AbsenceClaims::isClaim).toList();
  }

  /** The sentences of {@code answer} in order, each with its whitespace collapsed. */
  public static List<String> sentences(String answer) {
    if (answer == null || answer.isBlank()) {
      return List.of();
    }
    return Arrays.stream(SENTENCE_END.split(answer))
        .map(sentence -> sentence.replaceAll("[\\s\\p{Z}]+", " ").strip())
        .filter(sentence -> !sentence.isEmpty())
        .toList();
  }

  /** Whether the single {@code sentence} claims that something is not in the sources. */
  public static boolean isClaim(String sentence) {
    return PATTERNS.stream().anyMatch(p -> p.matcher(sentence).find());
  }

  private static Pattern pattern(String regex) {
    return Pattern.compile(
        regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
  }
}
