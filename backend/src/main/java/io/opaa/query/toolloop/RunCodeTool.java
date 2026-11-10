package io.opaa.query.toolloop;

import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatAttachmentService;
import io.opaa.common.ConflictException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.query.sandbox.SandboxProperties;
import io.opaa.query.sandbox.SandboxSession;
import io.opaa.query.sandbox.SandboxSession.OutputEntry;
import io.opaa.query.sandbox.SandboxSession.ScriptRun;
import io.opaa.query.sandbox.Sandboxes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs a Python script in the run's sandbox (ADR-0070 rights class isolated, ADR-0073). The first
 * call of a run opens one sandbox without network for this run alone; later calls of the same run
 * work in the same directory, so a correction builds on the files of the call before. When the run
 * has its answer, every file then lying in {@link Sandboxes#OUTPUT_DIR} becomes an attachment of
 * the asker in the chat's space, through every check of an ordinary upload, and is bound to the
 * answer; then the sandbox is deleted. Space and person come only from the {@link AgentRun}. A
 * failing script is reported to the model so it can correct it; an unreachable sandbox fails the
 * turn.
 */
@Component
@ConditionalOnProperty(name = "opaa.sandbox.enabled", havingValue = "true")
class RunCodeTool implements AgentTool {

  private static final Logger log = LoggerFactory.getLogger(RunCodeTool.class);

  /** Longest script the tool accepts; a longer one is refused before any sandbox exists. */
  static final int MAX_CODE_CHARS = 20_000;

  /** Longest part of stdout and of stderr that goes back to the model. */
  static final int MAX_OUTPUT_CHARS = 4_000;

  /** Lifetime of a sandbox beyond the run's own limit: collecting the files and deleting. */
  static final Duration LIFETIME_MARGIN = Duration.ofMinutes(2);

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
  private final Clock clock;

  RunCodeTool(
      Sandboxes sandboxes,
      SandboxProperties sandboxProperties,
      AgentToolLoopProperties loopProperties,
      ChatAttachmentService attachmentService,
      Clock clock) {
    this.sandboxes = sandboxes;
    this.sandboxProperties = sandboxProperties;
    this.loopProperties = loopProperties;
    this.attachmentService = attachmentService;
    this.clock = clock;
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
        + " ablegt, werden am Ende des Laufs der Person als Download an die Antwort gehängt"
        + " (z. B. PDF, DOCX, XLSX, CSV), höchstens "
        + Sandboxes.MAX_OUTPUT_FILES
        + ". Weitere Aufrufe in diesem Lauf arbeiten im selben Verzeichnis weiter. Die Umgebung"
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
    Duration remaining = Duration.between(clock.instant(), run.deadline());
    if (remaining.isNegative() || remaining.isZero()) {
      return ToolCallOutcome.refused(
          ToolCallResult.TIMEOUT, "Für ein Skript ist in diesem Lauf keine Zeit mehr.");
    }
    SandboxSession session =
        run.resource(
                RunSandbox.class,
                () -> new RunSandbox(sandboxes.open(lifetime(remaining)), attachmentService, run))
            .session();
    Duration timeout =
        sandboxProperties.runTimeout().compareTo(remaining) < 0
            ? sandboxProperties.runTimeout()
            : remaining;
    ScriptRun result = session.run(code, timeout);
    List<OutputEntry> entries = session.outputEntries();
    return new ToolCallOutcome(
        report(result, entries), resultOf(result), detail(entries), null, List.of());
  }

  /** The run's remaining time, the two model calls that may still follow and the margin. */
  private Duration lifetime(Duration remaining) {
    return remaining
        .plus(loopProperties.closingCallAllowance().multipliedBy(2))
        .plus(LIFETIME_MARGIN);
  }

  private static ToolCallResult resultOf(ScriptRun result) {
    if (result.timedOut()) {
      return ToolCallResult.TIMEOUT;
    }
    // A failing script is the model's to correct: the argument it wrote did not work.
    return result.exitCode() == 0 ? ToolCallResult.SUCCESS : ToolCallResult.INVALID_ARGUMENTS;
  }

  static String report(ScriptRun result, List<OutputEntry> entries) {
    StringBuilder report = new StringBuilder();
    report.append(
        result.timedOut()
            ? "Abgebrochen: Zeitlimit überschritten.\n"
            : "Exit-Code: " + result.exitCode() + "\n");
    appendStream(report, "Standardausgabe", result.stdout());
    appendStream(report, "Fehlerausgabe", result.stderr());
    if (entries.isEmpty()) {
      report.append("Keine Dateien in ").append(Sandboxes.OUTPUT_DIR).append(".\n");
      return report.toString();
    }
    report.append("Dateien in ").append(Sandboxes.OUTPUT_DIR).append(":\n");
    for (int i = 0; i < entries.size(); i++) {
      OutputEntry entry = entries.get(i);
      report.append("- ").append(entry.name()).append(" (").append(entry.size()).append(" Bytes)");
      if (i >= Sandboxes.MAX_OUTPUT_FILES) {
        report.append(" – wird nicht angehängt, zu viele Dateien");
      } else if (entry.size() > Sandboxes.MAX_OUTPUT_FILE_BYTES) {
        report.append(" – wird nicht angehängt, zu groß");
      }
      report.append('\n');
    }
    return report.toString();
  }

  /** The names of the files the person will get, bounded; {@code null} for none. */
  static String detail(List<OutputEntry> entries) {
    if (entries.isEmpty()) {
      return null;
    }
    String names =
        entries.stream()
            .limit(Sandboxes.MAX_OUTPUT_FILES)
            .map(OutputEntry::name)
            .collect(Collectors.joining(", "));
    return names.length() > MAX_DETAIL_CHARS
        ? names.substring(0, MAX_DETAIL_CHARS - 1) + "…"
        : names;
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

  /**
   * The sandbox of one run. {@link #finish()} takes the files of the output directory as the
   * asker's attachments; a file a check refuses is left out and counted in the log, without name.
   */
  record RunSandbox(SandboxSession session, ChatAttachmentService attachmentService, AgentRun run)
      implements RunResource {

    @Override
    public List<ChatAttachment> finish() {
      List<ChatAttachment> attachments = new ArrayList<>();
      int refused = 0;
      for (SandboxSession.OutputFile file : session.collectOutput()) {
        try {
          attachments.add(
              attachmentService
                  .uploadGenerated(
                      run.spaceId(),
                      run.chatId(),
                      attachments.size(),
                      file.name(),
                      file.content(),
                      run.userId())
                  .attachment());
        } catch (ValidationException
            | PayloadTooLargeException
            | TooManyRequestsException
            | ServiceUnavailableException
            | ConflictException e) {
          refused++;
        }
      }
      if (refused > 0) {
        log.info("Agent run {}: {} sandbox file(s) refused as attachments", run.runId(), refused);
      }
      return attachments;
    }

    @Override
    public void close() {
      session.close();
    }
  }
}
