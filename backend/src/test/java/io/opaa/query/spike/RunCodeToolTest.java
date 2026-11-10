package io.opaa.query.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.chat.ChatAttachmentService;
import io.opaa.common.ValidationException;
import io.opaa.metadata.MetadataFilter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** What {@link RunCodeTool} reports back to the model, and what it refuses before any sandbox. */
class RunCodeToolTest {

  private final ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);

  private static ToolContext contextFor(ToolLoopRunState runState) {
    return new ToolContext(Map.of(SpikeSearchKnowledgeTool.RUN_STATE_KEY, runState));
  }

  @Test
  void aFailingScriptGoesBackToTheModelWithItsErrorOutput() {
    CodeSandbox sandbox =
        code -> new CodeSandbox.Run(1, "", "NameError: name 'x' is not defined", false, List.of());
    ToolLoopRunState runState =
        new ToolLoopRunState(
            Set.of(), MetadataFilter.NONE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    String report = new RunCodeTool(sandbox, attachmentService).runCode("x", contextFor(runState));

    assertThat(report)
        .contains("Exit-Code: 1")
        .contains("NameError")
        .contains("Keine Dateien in /workspace/out");
    assertThat(runState.generatedFiles()).isEmpty();
    verifyNoInteractions(attachmentService);
  }

  @Test
  void aRejectedFileIsReportedAndNotKept() {
    UUID chatId = UUID.randomUUID();
    UUID spaceId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    byte[] content = {1, 2, 3};
    CodeSandbox sandbox =
        code ->
            new CodeSandbox.Run(
                0, "", "", false, List.of(new CodeSandbox.OutputFile("../evil.exe", content)));
    when(attachmentService.uploadGenerated(spaceId, chatId, 0, "../evil.exe", content, authorId))
        .thenThrow(new ValidationException("Das Dateiformat wird nicht unterstützt."));
    ToolLoopRunState runState =
        new ToolLoopRunState(Set.of(), MetadataFilter.NONE, chatId, spaceId, authorId);

    String report =
        new RunCodeTool(sandbox, attachmentService).runCode("print(1)", contextFor(runState));

    assertThat(report).contains("Datei abgelehnt: Das Dateiformat wird nicht unterstützt.");
    assertThat(runState.generatedFiles()).isEmpty();
  }

  @Test
  void withoutASpaceNoSandboxIsStarted() {
    CodeSandbox sandbox = mock(CodeSandbox.class);
    ToolLoopRunState runState = new ToolLoopRunState(Set.of(), MetadataFilter.NONE);

    String report =
        new RunCodeTool(sandbox, attachmentService).runCode("print(1)", contextFor(runState));

    assertThat(report).contains("nur in einem Chat");
    verifyNoInteractions(sandbox, attachmentService);
  }
}
