package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatAttachmentService;
import io.opaa.chat.ChatAttachmentView;
import io.opaa.common.ValidationException;
import io.opaa.query.sandbox.SandboxProperties;
import io.opaa.query.sandbox.SandboxSession;
import io.opaa.query.sandbox.SandboxSession.OutputEntry;
import io.opaa.query.sandbox.SandboxSession.OutputFile;
import io.opaa.query.sandbox.SandboxSession.ScriptRun;
import io.opaa.query.sandbox.Sandboxes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One sandbox per run (ADR-0073); its files become the asker's attachments at the run's end. */
class RunCodeToolTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");

  private final FakeSandboxes sandboxes = new FakeSandboxes();
  private final ChatAttachmentService attachments = mock(ChatAttachmentService.class);

  private RunCodeTool tool(Instant now) {
    return new RunCodeTool(
        sandboxes,
        new SandboxProperties(
            true,
            "http://sandbox:8090",
            "key",
            "img",
            Duration.ofSeconds(60),
            Duration.ofMinutes(15),
            "1",
            "1Gi"),
        AgentToolCallbackTest.properties(),
        attachments,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void callsOfOneRunShareOneSandboxAndItsFilesAreTakenAtTheEnd() {
    AgentRun run = run();
    RunCodeTool tool = tool(START);
    sandboxes.entries = List.of(new OutputEntry("bericht.pdf", 3));

    ToolCallOutcome first = tool.call(args("print(1)"), run);
    ToolCallOutcome second = tool.call(args("print(2)"), run);

    assertThat(sandboxes.opened).isEqualTo(1);
    assertThat(sandboxes.scripts).containsExactly("print(1)", "print(2)");
    assertThat(first.result()).isEqualTo(ToolCallResult.SUCCESS);
    assertThat(second.detail()).isEqualTo("bericht.pdf");
    assertThat(second.modelText()).contains("bericht.pdf (3 Bytes)").contains("Exit-Code: 0");

    ChatAttachment attachment = mock(ChatAttachment.class);
    when(attachments.uploadGenerated(any(), any(), anyInt(), eq("bericht.pdf"), any(), any()))
        .thenReturn(new ChatAttachmentView(attachment, null, null, null));
    sandboxes.files = List.of(new OutputFile("bericht.pdf", new byte[] {1, 2, 3}));

    RunResource resource = run.resources().getFirst();
    assertThat(resource.finish()).containsExactly(attachment);
    verify(attachments)
        .uploadGenerated(
            eq(run.spaceId()), eq(run.chatId()), eq(0), eq("bericht.pdf"), any(), eq(run.userId()));
    resource.close();
    assertThat(sandboxes.closed).isEqualTo(1);
  }

  @Test
  void aFailingOrStoppedScriptIsReportedToTheModel() {
    AgentRun run = run();
    RunCodeTool tool = tool(START);
    sandboxes.next = new ScriptRun(1, "", "NameError: x", false);
    ToolCallOutcome failed = tool.call(args("x"), run);
    sandboxes.next = new ScriptRun(-1, "", "", true);
    ToolCallOutcome stopped = tool.call(args("while True: pass"), run);

    assertThat(failed.result()).isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(failed.modelText()).contains("Exit-Code: 1").contains("NameError: x");
    assertThat(stopped.result()).isEqualTo(ToolCallResult.TIMEOUT);
    assertThat(stopped.modelText()).contains("Zeitlimit");
  }

  @Test
  void theScriptTimeLimitNeverOutlastsTheRun() {
    AgentRun run = run();
    tool(START.plusSeconds(110)).call(args("print(1)"), run);

    assertThat(sandboxes.timeouts).containsExactly(Duration.ofSeconds(10));
  }

  @Test
  void badArgumentsAndAMissingChatAreRefusedBeforeAnySandbox() {
    RunCodeTool tool = tool(START);
    AgentRun withoutChat =
        new AgentRun(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            Set.of(),
            null,
            AgentRuns.LIMITS,
            START);

    assertThat(tool.call(args("print(1)"), withoutChat).result()).isEqualTo(ToolCallResult.DENIED);
    assertThat(tool.call(JSON.readTree("{}"), run()).result())
        .isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(tool.call(args("x".repeat(RunCodeTool.MAX_CODE_CHARS + 1)), run()).result())
        .isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(tool(START.plusSeconds(120)).call(args("print(1)"), run()).result())
        .isEqualTo(ToolCallResult.TIMEOUT);
    assertThat(sandboxes.opened).isZero();
  }

  @Test
  void aFileRefusedByTheUploadChecksIsLeftOut() {
    AgentRun run = run();
    tool(START).call(args("print(1)"), run);
    sandboxes.files = List.of(new OutputFile("evil.exe", new byte[] {1}));
    when(attachments.uploadGenerated(any(), any(), anyInt(), any(), any(), any()))
        .thenThrow(new ValidationException("Dateityp nicht erlaubt"));

    assertThat(run.resources().getFirst().finish()).isEmpty();
  }

  @Test
  void theReportMarksFilesBeyondTheBounds() {
    List<OutputEntry> entries = new ArrayList<>();
    entries.add(new OutputEntry("riesig.pdf", Sandboxes.MAX_OUTPUT_FILE_BYTES + 1));
    for (int i = 0; i < Sandboxes.MAX_OUTPUT_FILES; i++) {
      entries.add(new OutputEntry("f" + i + ".csv", 1));
    }

    String report = RunCodeTool.report(new ScriptRun(0, "", "", false), entries);

    assertThat(report).contains("riesig.pdf (" + (Sandboxes.MAX_OUTPUT_FILE_BYTES + 1));
    assertThat(report).contains("zu groß").contains("zu viele Dateien");
    assertThat(RunCodeTool.detail(List.of())).isNull();
    verifyNoInteractions(attachments);
  }

  private static AgentRun run() {
    return AgentRuns.run(Set.of(UUID.randomUUID()), null, AgentRuns.LIMITS, START);
  }

  private static JsonNode args(String code) {
    return JSON.createObjectNode().put("skript", code);
  }

  /** Records what the tool asks of the sandbox. */
  static final class FakeSandboxes implements Sandboxes {
    int opened;
    int closed;
    final List<String> scripts = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    ScriptRun next = new ScriptRun(0, "", "", false);
    List<OutputEntry> entries = List.of();
    List<OutputFile> files = List.of();

    @Override
    public SandboxSession open(Duration lifetime) {
      opened++;
      return new SandboxSession() {
        @Override
        public String id() {
          return "sbx-" + opened;
        }

        @Override
        public ScriptRun run(String code, Duration timeout) {
          scripts.add(code);
          timeouts.add(timeout);
          return next;
        }

        @Override
        public List<OutputEntry> outputEntries() {
          return entries;
        }

        @Override
        public List<OutputFile> collectOutput() {
          return files;
        }

        @Override
        public void close() {
          closed++;
        }
      };
    }
  }
}
