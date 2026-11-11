package io.opaa.query.toolloop;

import io.opaa.chat.ChatAttachmentService;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.query.answer.DocumentBlock;
import io.opaa.query.runlog.AgentRunLogWriter;
import io.opaa.query.sandbox.OutputSelection;
import io.opaa.query.sandbox.OutputSelection.Verdict;
import io.opaa.query.sandbox.SandboxProperties;
import io.opaa.query.sandbox.SandboxSession;
import io.opaa.query.sandbox.SandboxSession.OutputEntry;
import io.opaa.query.sandbox.SandboxSession.ScriptRun;
import io.opaa.query.sandbox.Sandboxes;
import io.opaa.template.DocxActiveContent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs a Python script in the run's sandbox (ADR-0070 rights class isolated, ADR-0073). The first
 * sandbox call of a run opens one {@link RunSandbox} without network for this run alone; later
 * calls of the same run, also of {@code fill_template}, work in the same directory, so a correction
 * builds on the files of the call before. When the run has its answer, every file then lying in
 * {@link Sandboxes#OUTPUT_DIR} becomes an attachment of the asker in the chat's space, through
 * every check of an ordinary upload, and is bound to the answer; then the sandbox is deleted. Space
 * and person come only from the {@link AgentRun}. A failing script is reported to the model so it
 * can correct it; an unreachable sandbox fails the call. A file that cannot be collected or
 * attached is left out without failing the answer.
 */
@Component
@ConditionalOnProperty(name = "opaa.sandbox.enabled", havingValue = "true")
class RunCodeTool implements AgentTool {

  /** Longest script the tool accepts; a longer one is refused before any sandbox exists. */
  static final int MAX_CODE_CHARS = 20_000;

  /** Longest part of stdout and of stderr that goes back to the model. */
  static final int MAX_OUTPUT_CHARS = 4_000;

  /** Longest step detail the person sees: the names of the files in the output directory. */
  static final int MAX_DETAIL_CHARS = 120;

  static final String NO_CHAT = "Code kann nur in einem Chat eines Space ausgeführt werden.";

  private static final String SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "skript": {
            "type": "string",
            "description": "Das vollständige Python-3-Skript."
          }
        },
        "required": ["skript"]
      }
      """;

  private final Sandboxes sandboxes;
  private final SandboxProperties sandboxProperties;
  private final AgentToolLoopProperties loopProperties;
  private final ChatAttachmentService attachmentService;
  private final SupportedDocumentFormats supportedFormats;
  private final AgentRunLogWriter runLogWriter;
  private final DocxActiveContent docxActiveContent;
  private final Clock clock;

  /**
   * @throws IllegalStateException when {@code opaa.sandbox.max-lifetime} is shorter than a run may
   *     need its sandbox, so the server would remove it while the run still works.
   */
  RunCodeTool(
      Sandboxes sandboxes,
      SandboxProperties sandboxProperties,
      AgentToolLoopProperties loopProperties,
      ChatAttachmentService attachmentService,
      SupportedDocumentFormats supportedFormats,
      AgentRunLogWriter runLogWriter,
      DocxActiveContent docxActiveContent,
      Clock clock) {
    this.sandboxes = sandboxes;
    this.sandboxProperties = sandboxProperties;
    this.loopProperties = loopProperties;
    this.attachmentService = attachmentService;
    this.supportedFormats = supportedFormats;
    this.runLogWriter = runLogWriter;
    this.docxActiveContent = docxActiveContent;
    this.clock = clock;
    RunSandbox.requireLifetime(sandboxProperties, loopProperties);
  }

  @Override
  public String name() {
    return ToolNames.RUN_CODE;
  }

  @Override
  public ToolRightsClass rightsClass() {
    return ToolRightsClass.ISOLATED;
  }

  @Override
  public String description() {
    return "Führt ein Python-3-Skript in einer abgeschotteten Umgebung ohne Netzzugang aus und gibt"
        + " Exit-Code, Standardausgabe und Fehlerausgabe zurück. Verfügbar sind u. a. reportlab,"
        + " fpdf2, python-docx, openpyxl, pandas und matplotlib; nachinstallieren geht nicht."
        + " Dateien, die das Skript im Verzeichnis "
        + Sandboxes.OUTPUT_DIR
        + " ablegt, werden am Ende des Laufs der Person als Download an die Antwort gehängt,"
        + " höchstens "
        + Sandboxes.MAX_OUTPUT_FILES
        + ", und nur in diesen Formaten: "
        + String.join(", ", supportedFormats.extensions())
        + ". Bilder werden nicht angehängt: ein Diagramm als PDF speichern oder in ein Dokument"
        + " einbetten. Weitere Aufrufe in diesem Lauf arbeiten im selben Verzeichnis weiter. Die Umgebung"
        + " sieht die Wissensbasis nicht: Inhalte aus Fundstellen müssen im Skript selbst stehen.";
  }

  @Override
  public String inputSchema() {
    return SCHEMA;
  }

  @Override
  public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
    if (run.chatId() == null || run.spaceId() == null) {
      return ToolCallOutcome.refused(ToolCallResult.DENIED, NO_CHAT);
    }
    JsonNode codeNode = arguments.get("skript");
    String code = codeNode != null && codeNode.isString() ? codeNode.asString() : null;
    if (code == null || code.isBlank()) {
      return ToolCallOutcome.refused(ToolCallResult.INVALID_ARGUMENTS, "Das Skript fehlt.");
    }
    if (code.length() > MAX_CODE_CHARS) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS,
          "Das Skript ist zu lang (höchstens " + MAX_CODE_CHARS + " Zeichen).");
    }
    Duration untilDeadline = Duration.between(clock.instant(), run.deadline());
    if (untilDeadline.isNegative() || untilDeadline.isZero()) {
      return ToolCallOutcome.refused(
          ToolCallResult.TIMEOUT, "Für ein Skript ist in diesem Lauf keine Zeit mehr.");
    }
    RunSandbox sandbox =
        run.resource(
            RunSandbox.class,
            () ->
                RunSandbox.open(
                    sandboxes,
                    RunSandbox.lifetime(untilDeadline, loopProperties),
                    untilDeadline,
                    attachmentService,
                    runLogWriter,
                    run,
                    name(),
                    docxActiveContent::isPassive));
    sandbox.admit(name(), this::attachable);
    SandboxSession session = sandbox.session();
    // Starting the sandbox took part of the run's time; the script gets only what is left.
    Duration remaining = Duration.between(clock.instant(), run.deadline());
    if (remaining.isNegative() || remaining.isZero()) {
      return ToolCallOutcome.refused(
          ToolCallResult.TIMEOUT, "Für ein Skript ist in diesem Lauf keine Zeit mehr.");
    }
    Duration timeout =
        sandboxProperties.runTimeout().compareTo(remaining) < 0
            ? sandboxProperties.runTimeout()
            : remaining;
    ScriptRun result = session.run(code, timeout);
    sandbox.scriptRan(result);
    List<OutputEntry> entries = session.outputEntries();
    List<Verdict> verdicts = OutputSelection.of(entries, this::attachable);
    return new ToolCallOutcome(
        report(result, entries, verdicts, run.blockId()),
        resultOf(result),
        detail(entries, verdicts),
        null,
        List.of());
  }

  /** Whether a file of this name can become an attachment at all. */
  private boolean attachable(String fileName) {
    return supportedFormats.isSupported(fileName);
  }

  private static ToolCallResult resultOf(ScriptRun result) {
    if (result.timedOut()) {
      return ToolCallResult.TIMEOUT;
    }
    // A failing script is the model's to correct: the argument it wrote did not work.
    return result.exitCode() == 0 ? ToolCallResult.SUCCESS : ToolCallResult.INVALID_ARGUMENTS;
  }

  /**
   * The result for the model. Everything the sandbox controls - output and file names - stands in
   * one data block of the run, cleaned like a document, so it cannot pose as an instruction.
   */
  static String report(
      ScriptRun result, List<OutputEntry> entries, List<Verdict> verdicts, String blockId) {
    List<String> lines = new ArrayList<>();
    appendStream(lines, "Standardausgabe", result.stdout());
    appendStream(lines, "Fehlerausgabe", result.stderr());
    if (entries.isEmpty()) {
      lines.add("Keine Dateien in " + Sandboxes.OUTPUT_DIR + ".");
    } else {
      lines.add("Dateien in " + Sandboxes.OUTPUT_DIR + ":");
      for (int i = 0; i < entries.size(); i++) {
        OutputEntry entry = entries.get(i);
        lines.add("- " + entry.name() + " (" + entry.size() + " Bytes)" + note(verdicts.get(i)));
      }
    }
    String head =
        result.timedOut()
            ? "Abgebrochen: Zeitlimit überschritten."
            : "Exit-Code: " + result.exitCode();
    return head + "\n" + DocumentBlock.renderIndentedLines(blockId, lines);
  }

  private static String note(Verdict verdict) {
    return switch (verdict) {
      case TAKEN -> "";
      case FORMAT -> " – wird nicht angehängt, Format nicht erlaubt";
      case TOO_LARGE -> " – wird nicht angehängt, zu groß";
      case TOO_MANY -> " – wird nicht angehängt, zu viele Dateien";
    };
  }

  /** The names of the files the person will get, bounded; {@code null} for none. */
  static String detail(List<OutputEntry> entries, List<Verdict> verdicts) {
    List<String> taken = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      if (verdicts.get(i) == Verdict.TAKEN) {
        taken.add(entries.get(i).name());
      }
    }
    if (taken.isEmpty()) {
      return null;
    }
    String names = String.join(", ", taken);
    return names.length() > MAX_DETAIL_CHARS
        ? names.substring(0, MAX_DETAIL_CHARS - 1) + "…"
        : names;
  }

  private static void appendStream(List<String> lines, String label, String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    String bounded =
        text.length() > MAX_OUTPUT_CHARS
            ? "[Anfang gekürzt] " + text.substring(text.length() - MAX_OUTPUT_CHARS)
            : text;
    lines.add(label + ":");
    lines.addAll(bounded.lines().toList());
  }
}
