package io.opaa.llm;

/**
 * Rules of a vocabulary list for speech input and how the installation's and the person's list
 * become the {@code prompt} of one transcription. Whisper-compatible models read only the last ~224
 * tokens of a prompt; the person's list therefore comes last, so it is the part that always takes
 * effect. Each list holds at most {@link #MAX_LENGTH} characters.
 */
public final class TranscriptionVocabulary {

  /** The longest list, in characters, for the installation and for a person alike. */
  public static final int MAX_LENGTH = 500;

  /** The message of a list {@link #isValid} refuses. */
  public static final String RULE =
      "Die Begriffsliste umfasst höchstens " + MAX_LENGTH + " Zeichen.";

  private TranscriptionVocabulary() {}

  /** A list as stored: stripped; {@code null} stays {@code null}. */
  public static String normalised(String vocabulary) {
    return vocabulary == null ? null : vocabulary.strip();
  }

  /** Whether a {@link #normalised} list may be stored. */
  public static boolean isValid(String normalised) {
    return normalised == null || normalised.length() <= MAX_LENGTH;
  }

  /**
   * The prompt for one transcription: the installation's list, then the person's; {@code null} when
   * both are empty, so no prompt is sent at all.
   */
  public static String prompt(String installation, String personal) {
    String first = installation == null ? "" : installation.strip();
    String second = personal == null ? "" : personal.strip();
    if (first.isEmpty()) {
      return second.isEmpty() ? null : second;
    }
    if (second.isEmpty()) {
      return first;
    }
    char last = first.charAt(first.length() - 1);
    String separator = ".,;:!?".indexOf(last) >= 0 ? " " : ", ";
    return first + separator + second;
  }
}
