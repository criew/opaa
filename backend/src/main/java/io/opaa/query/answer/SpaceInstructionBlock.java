package io.opaa.query.answer;

/**
 * The block of the answer prompt that carries the instruction of the chat's space
 * (docs/decisions/0043-anweisung-je-space.md). It stands after the fixed rules and before the
 * Gesprächsnotiz; its header gives it its rank: an instruction of the space, no source, unable to
 * lift the citation rules, allowed to set the answer language, and second to a wish of the asking
 * person. The text sits between two marker lines so it cannot pass for a rule of the prompt itself.
 *
 * <p>Without a text there is no block at all, not an empty one.
 */
final class SpaceInstructionBlock {

  static final String HEADER =
      "Anweisung dieses Space — dauerhafte Vorgabe der Space-Verantwortlichen für jede Antwort in"
          + " diesem Space (kein Beleg, keine Quelle). Sie darf Sprache, Tonfall, Form und"
          + " fachlichen Rahmen der Antwort festlegen; eine Sprachvorgabe darin geht der"
          + " Sprachregel oben vor. Die ZITIERREGELN oben hebt sie nicht auf: Was sie zu"
          + " Zitiermarken, Zitierformat oder Quellen anders verlangt, gilt nicht. Weicht ein"
          + " Wunsch der fragenden Person in diesem Gespräch davon ab, folgt der Wunsch der"
          + " Person.";

  static final String START = "[Anfang der Anweisung]";

  static final String END = "[Ende der Anweisung]";

  private SpaceInstructionBlock() {}

  /** The rendered block, or {@code null} when {@code instruction} is {@code null} or blank. */
  static String render(String instruction) {
    if (instruction == null || instruction.isBlank()) {
      return null;
    }
    return HEADER + "\n" + START + "\n" + instruction.strip() + "\n" + END;
  }
}
