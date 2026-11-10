package io.opaa.query.toolloop;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Asks the person back (ADR-0070, Entscheidung 1). The question ends the run: it becomes the answer
 * kept in the chat, no further model call follows, and the run's place in the workspace is given
 * back. The person's reply is a new request with a run of its own, whose context is the chat
 * history. No run waits on an instance.
 */
@Component
class AskUserTool implements AgentTool {

  static final int MAX_QUESTION_CHARS = 1000;

  static final String ASKED =
      "Die Rückfrage geht an die Person; der Lauf endet hier. Rufe kein Werkzeug mehr auf.";

  private static final String SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "frage": {
            "type": "string",
            "description": "Die Rückfrage an die Person, auf Deutsch, kurz und eindeutig."
          }
        },
        "required": ["frage"]
      }
      """;

  @Override
  public String name() {
    return ToolNames.ASK_USER;
  }

  @Override
  public ToolRightsClass rightsClass() {
    return ToolRightsClass.READING;
  }

  @Override
  public String description() {
    return "Stellt der Person eine Rückfrage, wenn die Aufgabe ohne ihre Angabe nicht lösbar ist."
        + " Beendet den Lauf: Die Frage ist die Antwort, die Person antwortet in einer neuen"
        + " Nachricht. Nicht für Fragen, die eine Suche klären kann.";
  }

  @Override
  public String inputSchema() {
    return SCHEMA;
  }

  @Override
  public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
    JsonNode node = arguments.get("frage");
    String question = node == null || !node.isString() ? null : node.asString().strip();
    if (question == null || question.isEmpty()) {
      return ToolCallOutcome.refused(ToolCallResult.INVALID_ARGUMENTS, "Die Frage fehlt.");
    }
    if (question.length() > MAX_QUESTION_CHARS) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS,
          "Die Frage ist zu lang (höchstens " + MAX_QUESTION_CHARS + " Zeichen).");
    }
    run.askUser(question);
    return new ToolCallOutcome(ASKED, ToolCallResult.SUCCESS, null, null, List.of());
  }
}
