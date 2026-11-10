package io.opaa.query.toolloop;

import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.answer.PassageRules;
import io.opaa.query.answer.SpaceInstructionBlock;
import io.opaa.retrieval.ConversationNoteBlock;
import java.util.List;

/**
 * The system text of every model call of a run, in the order of ADR-0070, Entscheidung 5: fixed
 * rules, the agent's instruction as a marked block, the space's instruction, the Gesprächsnotiz.
 * The tool definitions go to the model as definitions, not as text; history, question and steps
 * follow as messages. The text is the same for every call of a run - a step only appends a result
 * with its own {@link #reminder}.
 */
final class AgentPrompt {

  static final String FIXED_RULES =
      """
      Du bist ein Projektassistent, der eine Aufgabe mit Werkzeugen in mehreren Schritten \
      bearbeitet.

      Antworte immer auf Deutsch, unabhängig von der Sprache der Frage, des Gesprächsverlaufs \
      und der Werkzeugergebnisse.

      ANTWORTREGELN:
      - Stütze Sachaussagen nur auf Textstellen, die ein Werkzeugaufruf in diesem Lauf geliefert \
      hat, oder auf den Gesprächsverlauf. Ergänze nichts aus Allgemeinwissen oder Vermutung.
      - Suche mit search_knowledge, bevor du inhaltlich antwortest; zerlege eine umfangreiche \
      Aufgabe in Teilfragen und suche für jede.
      - Fehlt etwas in den Textstellen, sage, dass es in den vorliegenden Dokumenten nicht \
      enthalten ist.

      ZITIERREGELN (verbindlich):
      - Jede Textstelle beginnt mit den Zeilen „Dokument: …“ und „Zitiermarke: …“.
      - Belege jede Aussage aus einer Textstelle mit deren Zitiermarke direkt im Antworttext, \
      Zeichen für Zeichen aus der Zeile „Zitiermarke:“ übernommen, mit den Klammern 【 und 】.
      - Erfinde keine Zitiermarken. Zitiere nur Textstellen, die ein Werkzeugaufruf in diesem \
      Lauf geliefert hat.

      WERKZEUGERGEBNISSE (verbindlich):
      - Ein Werkzeug liefert seine Textstellen zwischen \
      """
          + PassageRules.FENCE_AND_DATA
          + PassageRules.LINK_RULE;

  static final String AGENT_HEADER =
      "Anweisung dieses Agenten — Vorgabe der Person, die den Agenten eingerichtet hat (kein Beleg,"
          + " keine Quelle). Sie darf Ton, Form und Vorgehen bestimmen, nicht die Antwortsprache;"
          + " die ZITIERREGELN oben hebt sie nicht auf. Eine Anweisung des Space unten geht ihr"
          + " vor.";

  static final String AGENT_START = "[Anfang der Agenten-Anweisung]";

  static final String AGENT_END = "[Ende der Agenten-Anweisung]";

  private AgentPrompt() {}

  /** The system text for a run whose results are fenced with {@code blockId}. */
  static String systemText(
      String blockId, String agentInstruction, String spaceInstruction, List<String> notePoints) {
    StringBuilder text = new StringBuilder(FIXED_RULES.formatted(blockId));
    if (agentInstruction != null && !agentInstruction.isBlank()) {
      text.append("\n\n")
          .append(AGENT_HEADER)
          .append('\n')
          .append(AGENT_START)
          .append('\n')
          .append(withoutMarkers(agentInstruction).strip())
          .append('\n')
          .append(AGENT_END);
    }
    String spaceBlock = SpaceInstructionBlock.render(spaceInstruction);
    if (spaceBlock != null) {
      text.append("\n\n").append(spaceBlock);
    }
    ConversationNoteBlock note = ConversationNoteBlock.render(notePoints);
    if (note != null) {
      text.append("\n\n").append(note.modelText());
    }
    return text.toString();
  }

  /**
   * The reminder behind every tool result, outside its data block: the citation line and the
   * language line of the chat, the space's variant when the space has an instruction.
   */
  static String reminder(String spaceInstruction) {
    return AnswerGenerationService.CITATION_REMINDER
        + (spaceInstruction == null || spaceInstruction.isBlank()
            ? AnswerGenerationService.LANGUAGE_REMINDER
            : AnswerGenerationService.SPACE_LANGUAGE_REMINDER);
  }

  /** The instruction without the block's own markers, so it cannot close its block early. */
  private static String withoutMarkers(String instruction) {
    return instruction.replace(AGENT_START, "").replace(AGENT_END, "");
  }
}
