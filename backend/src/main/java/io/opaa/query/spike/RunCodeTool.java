package io.opaa.query.spike;

import io.opaa.chat.ChatAttachmentService;
import io.opaa.chat.ChatAttachmentView;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The second tool of the agent spike: runs a Python script in a {@link CodeSandbox} and takes every
 * file it leaves in {@link CodeSandbox#OUTPUT_DIR} as an attachment of the asker in the chat's
 * space, through every check of an ordinary upload. Space and person come exclusively from the
 * {@link ToolLoopRunState}, never from a model argument; the attachments it creates stay unbound
 * until {@link SpikeToolLoopQueryHandler} binds them to the answer's message. A failing script is
 * reported back to the model so it can correct it; only an unreachable sandbox fails the turn.
 */
@Component
@ConditionalOnProperty(
    name = {"opaa.spike.tool-loop.enabled", "opaa.sandbox.enabled"},
    havingValue = "true")
class RunCodeTool {

  /** Must match the tool name {@link SpikeToolLoopQueryHandler}'s system prompt names. */
  static final String TOOL_NAME = "run_code";

  /** Longest script the tool accepts; a longer one is refused before any sandbox exists. */
  static final int MAX_CODE_CHARS = 20_000;

  /** Longest part of stdout and of stderr that goes back to the model. */
  static final int MAX_OUTPUT_CHARS = 4_000;

  private final CodeSandbox sandbox;
  private final ChatAttachmentService attachmentService;

  RunCodeTool(CodeSandbox sandbox, ChatAttachmentService attachmentService) {
    this.sandbox = sandbox;
    this.attachmentService = attachmentService;
  }

  @Tool(
      name = TOOL_NAME,
      description =
          "Führt ein Python-3-Skript in einer abgeschotteten Umgebung ohne Netzzugang aus und gibt"
              + " Exit-Code, Standardausgabe und Fehlerausgabe zurück. Verfügbar sind u. a."
              + " reportlab, fpdf2, python-docx, openpyxl, pandas und matplotlib. Dateien, die das"
              + " Skript im Verzeichnis "
              + CodeSandbox.OUTPUT_DIR
              + " ablegt, werden der Person als Download an die Antwort gehängt (z. B. PDF, DOCX,"
              + " XLSX, CSV). Jeder Aufruf startet in einer leeren Umgebung: Inhalte aus der"
              + " Wissensbasis müssen im Skript selbst stehen.")
  String runCode(String code, ToolContext toolContext) {
    ToolLoopRunState runState =
        (ToolLoopRunState) toolContext.getContext().get(SearchKnowledgeTool.RUN_STATE_KEY);
    if (runState.spaceId() == null) {
      return "Code kann nur in einem Chat eines Space ausgeführt werden.";
    }
    if (code == null || code.isBlank()) {
      return "Das Skript ist leer.";
    }
    if (code.length() > MAX_CODE_CHARS) {
      return "Das Skript ist zu lang (höchstens " + MAX_CODE_CHARS + " Zeichen).";
    }
    runState.codeRuns().incrementAndGet();
    CodeSandbox.Run run = sandbox.run(code);

    StringBuilder report = new StringBuilder();
    report.append(
        run.timedOut()
            ? "Abgebrochen: Zeitlimit überschritten.\n"
            : "Exit-Code: " + run.exitCode() + "\n");
    appendStream(report, "Standardausgabe", run.stdout());
    appendStream(report, "Fehlerausgabe", run.stderr());
    if (run.files().isEmpty()) {
      report.append("Keine Dateien in ").append(CodeSandbox.OUTPUT_DIR).append(".\n");
    }
    for (CodeSandbox.OutputFile file : run.files()) {
      try {
        ChatAttachmentView view =
            attachmentService.uploadGenerated(
                runState.spaceId(), file.name(), file.content(), runState.authorId());
        runState.generatedFiles().add(view.attachment());
        report
            .append("Datei bereitgestellt: ")
            .append(view.attachment().getFileName())
            .append(" (")
            .append(view.attachment().getSizeBytes())
            .append(" Bytes)\n");
      } catch (ValidationException | PayloadTooLargeException e) {
        report.append("Datei abgelehnt: ").append(e.getMessage()).append('\n');
      }
    }
    return report.toString();
  }

  private static void appendStream(StringBuilder report, String label, String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    String bounded =
        text.length() > MAX_OUTPUT_CHARS
            ? "[Anfang gekürzt] " + text.substring(text.length() - MAX_OUTPUT_CHARS)
            : text;
    report.append(label).append(":\n").append(bounded).append('\n');
  }
}
