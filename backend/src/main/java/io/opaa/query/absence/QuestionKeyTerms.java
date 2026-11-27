package io.opaa.query.absence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key terms of a question, read without a model: identifiers (a token with digits and letters
 * or separators such as {@code AB-08} or {@code 4.2}, a paragraph such as {@code § 34}, an
 * uppercase name of six letters or more) and content words (five letters or more, a number of three
 * digits or more, or a short uppercase abbreviation such as {@code DSGVO}, outside a fixed list of
 * question and filler words).
 *
 * <p>A passage {@link #matches} when it contains one of the identifiers, or - for a question
 * without any - at least two of the content words (all of them when there are fewer): a short
 * abbreviation alone is too common to single out a passage. A content word of five characters or
 * more matches by its stem, its leading characters without the last three (at least five), so
 * "Gebühr" finds "Gebühren"; a shorter one and an identifier match only as a whole token.
 */
public record QuestionKeyTerms(List<String> identifiers, List<String> words) {

  static final int MAX_IDENTIFIERS = 6;
  static final int MAX_WORDS = 8;

  private static final Pattern PARAGRAPH = Pattern.compile("§+\\s*(\\d+[a-z]?)");
  private static final Pattern TOKEN =
      Pattern.compile("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}./-]*[\\p{L}\\p{N}])?");
  private static final Pattern NUMBER = Pattern.compile("\\p{N}{3,}");
  private static final Pattern NAME = Pattern.compile("\\p{Lu}{6,12}");
  private static final Pattern SHORT_ABBREVIATION = Pattern.compile("\\p{Lu}{2,5}");

  private static final Set<String> STOP_WORDS =
      Set.of(
          "welche",
          "welcher",
          "welches",
          "welchen",
          "welchem",
          "wieviel",
          "wieviele",
          "warum",
          "weshalb",
          "wieso",
          "werden",
          "wurde",
          "wurden",
          "würde",
          "würden",
          "worden",
          "können",
          "könnte",
          "könnten",
          "müssen",
          "müsste",
          "sollen",
          "sollte",
          "sollten",
          "dürfen",
          "darf",
          "haben",
          "hatte",
          "hätte",
          "einer",
          "eines",
          "einem",
          "einen",
          "diese",
          "dieser",
          "dieses",
          "diesen",
          "diesem",
          "keine",
          "keinen",
          "keiner",
          "nicht",
          "nichts",
          "geben",
          "gibt's",
          "steht",
          "stehen",
          "darin",
          "darüber",
          "hierzu",
          "dafür",
          "davon",
          "bitte",
          "sagen",
          "nennen",
          "nenne",
          "erkläre",
          "erklären",
          "beschreibe",
          "beschreiben",
          "zusammen",
          "zusammenfassung",
          "fasse",
          "informationen",
          "information",
          "angaben",
          "angabe",
          "quelle",
          "quellen",
          "dokument",
          "dokumente",
          "dokumenten",
          "unterlagen",
          "genau",
          "gerade",
          "eigentlich",
          "zwischen",
          "wobei",
          "sowie",
          "außerdem",
          "jemand",
          "etwas",
          "alles",
          "allen",
          "aller",
          "anderen",
          "andere",
          "anderer",
          "meine",
          "meinem",
          "meiner",
          "meinen",
          "unsere",
          "unserer",
          "unseren",
          "ihrer",
          "ihren",
          "deren",
          "dessen",
          "woher",
          "wohin",
          "womit",
          "wofür",
          "worauf",
          "wozu",
          "wodurch",
          "gilt",
          "gelten",
          "aktuell",
          "aktuelle",
          "aktuellen",
          "derzeit",
          "insgesamt",
          "jeweils",
          "konkret",
          "bezüglich",
          "hinsichtlich",
          "about",
          "which",
          "there",
          "their",
          "would",
          "could",
          "should",
          "where",
          "these",
          "those",
          "other",
          "please",
          "documents",
          "sources",
          "explain",
          "describe",
          "being",
          "after",
          "before",
          "between",
          "whose");

  /** The key terms of {@code question}; empty lists for a question without any. */
  public static QuestionKeyTerms of(String question) {
    if (question == null || question.isBlank()) {
      return new QuestionKeyTerms(List.of(), List.of());
    }
    Set<String> identifiers = new LinkedHashSet<>();
    Set<String> words = new LinkedHashSet<>();
    Matcher paragraph = PARAGRAPH.matcher(question);
    while (paragraph.find()) {
      identifiers.add("§ " + paragraph.group(1));
    }
    Matcher token = TOKEN.matcher(PARAGRAPH.matcher(question).replaceAll(" "));
    while (token.find()) {
      String value = token.group();
      if (isIdentifier(value)) {
        identifiers.add(value);
      } else if (SHORT_ABBREVIATION.matcher(value).matches()) {
        words.add(value.toLowerCase(Locale.ROOT));
      } else if (NUMBER.matcher(value).matches()) {
        words.add(value);
      } else if (value.codePoints().allMatch(Character::isLetter) && value.length() >= 5) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!STOP_WORDS.contains(lower)) {
          words.add(lower);
        }
      }
    }
    return new QuestionKeyTerms(
        identifiers.stream().limit(MAX_IDENTIFIERS).toList(),
        words.stream().limit(MAX_WORDS).toList());
  }

  /** Whether the question carries nothing to search for. */
  public boolean isEmpty() {
    return identifiers.isEmpty() && words.isEmpty();
  }

  /** The key terms as one search query, identifiers first. */
  public String searchQuery() {
    List<String> terms = new ArrayList<>(identifiers);
    terms.addAll(words);
    return String.join(" ", terms);
  }

  /** Whether {@code text} carries the key terms as described on the type. */
  public boolean matches(String text) {
    if (text == null || text.isBlank() || isEmpty()) {
      return false;
    }
    String normalized = normalize(text);
    if (!identifiers.isEmpty()) {
      return identifiers.stream()
          .anyMatch(identifier -> containsToken(normalized, normalize(identifier)));
    }
    long found = words.stream().filter(word -> containsWord(normalized, word)).count();
    return found >= Math.min(2, words.size());
  }

  /**
   * Whether {@code sentence} names any of the key terms at all - one identifier or one content
   * word: the test whether an absence claim is about the question rather than a side remark.
   */
  public boolean mentionedIn(String sentence) {
    if (sentence == null || sentence.isBlank() || isEmpty()) {
      return false;
    }
    String normalized = normalize(sentence);
    return identifiers.stream()
            .anyMatch(identifier -> containsToken(normalized, normalize(identifier)))
        || words.stream().anyMatch(word -> containsWord(normalized, word));
  }

  private static boolean isIdentifier(String token) {
    boolean digit = token.codePoints().anyMatch(Character::isDigit);
    boolean letter = token.codePoints().anyMatch(Character::isLetter);
    boolean separator =
        token.indexOf('-') >= 0 || token.indexOf('.') >= 0 || token.indexOf('/') >= 0;
    if (digit && (letter || separator)) {
      return true;
    }
    return NAME.matcher(token).matches();
  }

  private static String normalize(String text) {
    return text.toLowerCase(Locale.ROOT)
        .replaceAll("§+[\\s\\p{Z}]*", "§ ")
        .replaceAll("[\\s\\p{Z}]+", " ");
  }

  private static boolean containsToken(String text, String token) {
    return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "(?![\\p{L}\\p{N}])")
        .matcher(text)
        .find();
  }

  private static boolean containsWord(String text, String word) {
    if (word.length() < 5) {
      return containsToken(text, word);
    }
    String stem = word.substring(0, Math.max(Math.min(5, word.length()), word.length() - 3));
    return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(stem)).matcher(text).find();
  }
}
