package io.opaa.query.absence;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognises an answer that says something is not in the sources - "in den vorliegenden Dokumenten
 * nicht enthalten", "keine Angaben", "liegen keine Informationen vor", "not mentioned in the
 * provided documents". A fixed list of German and English phrasings, matched without a model call:
 * the same text always gets the same verdict. A false positive costs one search, never an answer.
 */
public final class AbsenceClaims {

  private static final String SOURCES =
      "(?:vorliegenden\\s+|bereitgestellten\\s+|verfügbaren\\s+|vorhandenen\\s+|gegebenen\\s+"
          + "|abgerufenen\\s+|genannten\\s+|übermittelten\\s+|zitierten\\s+)?"
          + "(?:quellen|dokumenten|dokumente|kontextdokumenten|kontextdokumente|unterlagen"
          + "|kontextabschnitten|abschnitten|textstellen|fundstellen|materialien|texten)";

  private static final String MISSING =
      "(?:enthalten|genannt|aufgeführt|erwähnt|angegeben|vorhanden|geregelt|beschrieben"
          + "|ersichtlich|hervor|zu\\s+entnehmen|entnehmen|zu\\s+finden|gefunden|auffindbar"
          + "|festgelegt|benannt|beziffert)";

  private static final String EN_SOURCES =
      "(?:provided\\s+|given\\s+|available\\s+|retrieved\\s+|supplied\\s+)?"
          + "(?:sources|documents|context|passages|material|excerpts)";

  private static final List<Pattern> PATTERNS =
      List.of(
          // "ist in den vorliegenden Dokumenten nicht enthalten"
          pattern(
              "\\b(?:in|aus)\\s+den\\s+"
                  + SOURCES
                  + "\\b(?:\\s+\\S+){0,6}?\\s+nicht\\s+"
                  + MISSING),
          // "nicht in den Quellen enthalten", "keine Angaben in den Unterlagen"
          pattern(
              "\\b(?:nicht|keine[nr]?)\\s+(?:\\S+\\s+){0,4}?(?:in|aus)\\s+den\\s+"
                  + SOURCES
                  + "\\b"),
          // "die Quellen enthalten dazu keine Angabe", "die Dokumente nennen das nicht"
          pattern(
              "\\bdie\\s+"
                  + SOURCES
                  + "\\s+(?:enthalten|nennen|erwähnen|regeln|beschreiben|geben|liefern|sagen)"
                  + "(?:\\s+\\S+){0,6}?\\s+(?:nicht|keine[nr]?)\\b"),
          // "keine (näheren) Angaben", "keine Informationen"
          pattern(
              "\\bkeine\\s+(?:näheren\\s+|weiteren\\s+|konkreten\\s+|genauen\\s+|expliziten\\s+)?"
                  + "(?:angaben?|informationen|hinweise)\\b"),
          // "liegen (mir) keine Informationen vor"
          pattern("\\bliegen\\s+(?:mir\\s+|uns\\s+)?keine\\s+\\S+\\s+vor\\b"),
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
          pattern("\\bno\\s+(?:information|details|mention|data)\\s+(?:about|on|regarding|in)\\b"),
          pattern("\\bthere\\s+is\\s+no\\s+(?:information|mention)\\b"),
          pattern("\\b(?:could\\s*not|couldn't|cannot|can't|unable\\s+to)\\s+find\\b"));

  private AbsenceClaims() {}

  /** Whether {@code answer} claims, anywhere in its text, that something is not in the sources. */
  public static boolean claimsAbsence(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    String text = answer.replaceAll("[\\s\\p{Z}]+", " ");
    return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
  }

  private static Pattern pattern(String regex) {
    return Pattern.compile(
        regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
  }
}
