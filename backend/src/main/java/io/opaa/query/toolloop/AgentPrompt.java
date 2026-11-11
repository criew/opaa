package io.opaa.query.toolloop;

import io.opaa.common.PromptText;
import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.answer.PassageRules;
import io.opaa.query.answer.SpaceInstructionBlock;
import io.opaa.retrieval.ConversationNoteBlock;
import java.util.List;

/**
 * The system text of every model call of a run, in the order of ADR-0070, Entscheidung 5: fixed
 * rules (with the rules of the plan step, if the agent has one), the agent's instruction as a
 * marked block, the space's instruction, the Gesprächsnotiz. The tool definitions go to the model
 * as definitions, not as text; history, question and steps follow as messages. The text is the same
 * for every call of a run - a step only appends a result with its own {@link #reminder}.
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

  /** Added to the fixed rules of a plan run: the run answers with its plan and nothing else. */
  static final String PLAN_RULES =
      """
      PLANSCHRITT (verbindlich):
      - Dieser Agent legt zuerst einen Plan vor und arbeitet erst, wenn die Person ihn bestätigt \
      oder ändert.
      - Antworte jetzt nur mit dem Plan: eine nummerierte Liste der Teilfragen, denen du \
      nachgehen willst, und je Teilfrage, wo du suchen willst.
      - Suche noch nicht, beantworte die Frage noch nicht und zitiere nichts.
      - Ist die Frage unklar, nenne im Plan, was du annimmst.
      - Schließe mit der Bitte, den Plan zu bestätigen oder zu ändern.""";

  /** Added to the fixed rules of the run that follows a plan. */
  static final String CONFIRMED_PLAN =
      """
      BESTÄTIGTER PLAN (verbindlich):
      - Deine letzte Antwort im Gesprächsverlauf ist ein Plan. Die neue Nachricht der Person \
      bestätigt oder ändert ihn.
      - Arbeite den Plan mit den Änderungen der Person jetzt ab und lege keinen neuen Plan vor.
      - Beginne sofort mit search_knowledge; eine Antwort ohne Suche ist keine Antwort.""";

  /** Added to the fixed rules of the run that answers a question back of a plan's working run. */
  static final String RESUMED_PLAN =
      """
      BESTÄTIGTER PLAN (verbindlich):
      - Du hast einen von der Person bestätigten Plan abgearbeitet und dabei eine Rückfrage \
      gestellt. Die neue Nachricht der Person beantwortet sie.
      - Arbeite den Plan mit dieser Angabe fort und lege keinen neuen Plan vor.""";

  /** What a run does with a plan step. */
  enum PlanPhase {
    /** The agent has no plan step, or ended its last run differently. */
    NONE,
    /** The run answers with a plan only. */
    PLAN,
    /** The run works off the plan the person confirmed or changed. */
    CONFIRMED,
    /** The run continues a confirmed plan after the person answered its question back. */
    RESUMED
  }

  private AgentPrompt() {}

  /** The system text for a run whose results are fenced with {@code blockId}. */
  static String systemText(
      String blockId, String agentInstruction, String spaceInstruction, List<String> notePoints) {
    return systemText(blockId, agentInstruction, spaceInstruction, notePoints, PlanPhase.NONE);
  }

  /** The system text for a run in the given phase of its plan step. */
  static String systemText(
      String blockId,
      String agentInstruction,
      String spaceInstruction,
      List<String> notePoints,
      PlanPhase phase) {
    StringBuilder text = new StringBuilder(FIXED_RULES.formatted(blockId));
    switch (phase) {
      case PLAN -> text.append("\n\n").append(PLAN_RULES);
      case CONFIRMED -> text.append("\n\n").append(CONFIRMED_PLAN);
      case RESUMED -> text.append("\n\n").append(RESUMED_PLAN);
      case NONE -> {}
    }
    if (agentInstruction != null && !agentInstruction.isBlank()) {
      text.append("\n\n")
          .append(AGENT_HEADER)
          .append('\n')
          .append(PromptText.between(AGENT_START, AGENT_END, agentInstruction));
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
}
