package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class AnswerGenerationServiceTest {

  @Mock private ChatModel chatModel;
  @InjectMocks private AnswerGenerationService answerGenerationService;

  @Test
  void generateAnswerBuildsCorrectPromptAndReturnsResponse() {
    var chunk1 = new Document("Chunk one text", Map.of("file_name", "doc1.md"));
    var chunk2 = new Document("Chunk two text", Map.of("file_name", "doc2.pdf"));

    var assistantMessage = new AssistantMessage("Generated answer");
    var generation = new Generation(assistantMessage);
    var chatResponse = new ChatResponse(List.of(generation));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result =
        answerGenerationService.generateAnswer("What is OPAA?", List.of(chunk1, chunk2));

    assertThat(result.getResult().getOutput().getText()).isEqualTo("Generated answer");

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());

    Prompt capturedPrompt = promptCaptor.getValue();
    assertThat(capturedPrompt.getInstructions()).hasSize(2);

    // System message contains context
    var systemMessage = capturedPrompt.getInstructions().get(0);
    assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
    assertThat(systemMessage.getText()).contains("doc1.md");
    assertThat(systemMessage.getText()).contains("doc2.pdf");
    assertThat(systemMessage.getText()).contains("Chunk one text");
    assertThat(systemMessage.getText()).contains("Chunk two text");

    // User message contains the question
    var userMessage = capturedPrompt.getInstructions().get(1);
    assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
    assertThat(userMessage.getText()).isEqualTo("What is OPAA?");
  }

  @Test
  void generateAnswerHandlesEmptyChunks() {
    var assistantMessage = new AssistantMessage("No context available");
    var generation = new Generation(assistantMessage);
    var chatResponse = new ChatResponse(List.of(generation));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result = answerGenerationService.generateAnswer("Question?", List.of());

    assertThat(result.getResult().getOutput().getText()).isEqualTo("No context available");
  }
}
