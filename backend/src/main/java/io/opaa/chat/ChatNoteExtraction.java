package io.opaa.chat;

import io.opaa.api.types.ChatNoteItemKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The prompt of the Gesprächsnotiz condensation and the defensive parsing of its answer (#1487,
 * docs/features/conversation-memory.md, "Bauteil 2") - free of Spring and of the model call, so the
 * rules below are testable without either.
 *
 * <p>Nothing here trusts the model's formatting: a leading bullet is stripped, a line whose kind
 * prefix is missing or unknown becomes {@link ChatNoteItemKind#ANTWORTFORM} (the kind that never
 * reaches the search, where a superfluous point does the least damage), a line longer than {@link
 * #MAX_TEXT_LENGTH} is shortened, and anything beyond {@link #MAX_POINTS_PER_TURN} lines is dropped
 * - a talkative message must not push the Rahmenangabe of turn 1 over the note's cap.
 */
public final class ChatNoteExtraction {

  private ChatNoteExtraction() {}

  /** Fixed value (specification, "Konfiguration: Ebenenzuordnung"): nobody reconfigures it. */
  public static final int MAX_POINTS_PER_TURN = 2;

  /** Fixed value: an Oberflächen- und Prompt-Größe, mirrored by the column width. */
  public static final int MAX_TEXT_LENGTH = 200;

  /** What the model answers when the message carries nothing worth keeping. */
  static final String NOTHING_SENTINEL = "KEINE";

  private static final Pattern LEADING_BULLET_OR_NUMBER =
      Pattern.compile("^[-*•]\\s+|^\\d+[.)]\\s+");

  /**
   * Deliberately describes the output format instead of demonstrating it, for the same reason
   * {@code QueryDecompositionService}'s prompt does: a small instruct model regularly returns an
   * example sentence verbatim as if it were the answer.
   */
  static final String PROMPT_TEMPLATE =
      """
      Aus der folgenden Nachricht einer Person sollst du dauerhafte Angaben über diese Person \
      festhalten, die auch für spätere Fragen im selben Gespräch gelten.

      Festhalten: Rolle, Zuständigkeit, Ort, Zeitraum, Fassung, Organisation, Festlegungen im \
      Gespräch sowie Wünsche zur Darstellung der Antwort.

      Nicht festhalten: das Thema der Frage, Inhalte möglicher Antworten, Bewertungen oder \
      Vermutungen über die Person, sowie die Frage selbst.

      Format: je Zeile genau eine Angabe, als ein kurzer Satz in der dritten Person, auf Deutsch, \
      höchstens %d Zeichen. Stelle jeder Zeile die Art voran, gefolgt von einem Doppelpunkt: \
      RAHMEN für Rolle, Zuständigkeit, Ort, Zeitraum, Fassung, Organisation und Festlegungen, \
      ANTWORTFORM für Wünsche zur Darstellung. Höchstens %d Zeilen. Enthält die Nachricht keine \
      solche Angabe, antworte ausschließlich mit %s.

      Nachricht: %s
      """;

  /** The condensation prompt for one user message. */
  public static String prompt(String userMessage) {
    return PROMPT_TEMPLATE.formatted(
        MAX_TEXT_LENGTH, MAX_POINTS_PER_TURN, NOTHING_SENTINEL, userMessage);
  }

  /**
   * The model's answer as note candidates, at most {@link #MAX_POINTS_PER_TURN} of them. Empty for
   * {@code null}, blank, the sentinel, or an answer no line of which carries usable text - all four
   * mean the same thing here, "this turn contributes nothing", which is not an error.
   */
  public static List<ChatNoteCandidate> parse(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      return List.of();
    }
    List<ChatNoteCandidate> candidates = new ArrayList<>(MAX_POINTS_PER_TURN);
    for (String rawLine : rawText.strip().lines().toList()) {
      String line = LEADING_BULLET_OR_NUMBER.matcher(rawLine.strip()).replaceFirst("").strip();
      if (line.isEmpty()) {
        continue;
      }
      if (isSentinel(line)) {
        // The sentinel ends the answer: a model that adds prose after it has said "nothing"
        // already, and anything following is not an angabe of the person.
        return List.of();
      }
      ChatNoteItemKind kind = kindOf(line);
      String text = shorten(textOf(line));
      if (text.isEmpty()) {
        continue;
      }
      candidates.add(new ChatNoteCandidate(text, kind));
      if (candidates.size() == MAX_POINTS_PER_TURN) {
        break;
      }
    }
    return List.copyOf(candidates);
  }

  private static boolean isSentinel(String line) {
    return normalizeToken(line).equals(NOTHING_SENTINEL.toLowerCase(Locale.ROOT));
  }

  /**
   * The kind named before the first colon, or {@link ChatNoteItemKind#ANTWORTFORM} when the line
   * names none or names something unknown - the specification's "Zeile ohne erkennbare Art" rule.
   */
  private static ChatNoteItemKind kindOf(String line) {
    int colon = line.indexOf(':');
    if (colon < 0) {
      return ChatNoteItemKind.ANTWORTFORM;
    }
    String prefix = normalizeToken(line.substring(0, colon));
    for (ChatNoteItemKind kind : ChatNoteItemKind.values()) {
      if (prefix.equals(kind.name().toLowerCase(Locale.ROOT))) {
        return kind;
      }
    }
    return ChatNoteItemKind.ANTWORTFORM;
  }

  /** The line without a recognized kind prefix; an unrecognized prefix stays part of the text. */
  private static String textOf(String line) {
    int colon = line.indexOf(':');
    if (colon < 0) {
      return line;
    }
    String prefix = normalizeToken(line.substring(0, colon));
    for (ChatNoteItemKind kind : ChatNoteItemKind.values()) {
      if (prefix.equals(kind.name().toLowerCase(Locale.ROOT))) {
        return line.substring(colon + 1).strip();
      }
    }
    return line;
  }

  /** Lower-cased and stripped of everything that is not a letter or digit. */
  private static String normalizeToken(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  static String shorten(String text) {
    String stripped = text.strip();
    return stripped.length() <= MAX_TEXT_LENGTH
        ? stripped
        : stripped.substring(0, MAX_TEXT_LENGTH - 1).stripTrailing() + "…";
  }
}
