package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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

  private ChatMemory chatMemory;
  private AnswerGenerationService answerGenerationService;

  @BeforeEach
  void setUp() {
    chatMemory = MessageWindowChatMemory.builder().build();
    ChatClient.Builder builder = ChatClient.builder(chatModel);
    answerGenerationService = new AnswerGenerationService(builder, chatMemory);
  }

  @Test
  void generateAnswerBuildsCorrectPromptAndReturnsResponse() {
    var chunk1 = new Document("Chunk one text", Map.of("file_name", "doc1.md"));
    var chunk2 = new Document("Chunk two text", Map.of("file_name", "doc2.pdf"));

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Generated answer"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result =
        answerGenerationService.generateAnswer(
            "What is OPAA?", List.of(chunk1, chunk2), "conv-123");

    assertThat(result.getResult().getOutput().getText()).isEqualTo("Generated answer");

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());

    Prompt capturedPrompt = promptCaptor.getValue();
    assertThat(capturedPrompt.getInstructions()).hasSize(2);

    var systemMessage = capturedPrompt.getInstructions().get(0);
    assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
    assertThat(systemMessage.getText()).contains("doc1.md");
    assertThat(systemMessage.getText()).contains("doc2.pdf");
    assertThat(systemMessage.getText()).contains("Chunk one text");

    var userMessage = capturedPrompt.getInstructions().get(1);
    assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
    assertThat(userMessage.getText()).isEqualTo("What is OPAA?");
  }

  @Test
  void generateAnswerHandlesEmptyChunks() {
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No context available"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result =
        answerGenerationService.generateAnswer("Question?", List.of(), "conv-456");

    assertThat(result.getResult().getOutput().getText()).isEqualTo("No context available");
  }

  @Test
  void generateAnswerPreservesConversationHistory() {
    var chatResponse1 =
        new ChatResponse(List.of(new Generation(new AssistantMessage("First answer"))));
    var chatResponse2 =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("Second answer with context"))));

    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse1, chatResponse2);

    String conversationId = "conv-history-test";

    answerGenerationService.generateAnswer("First question", List.of(), conversationId);
    answerGenerationService.generateAnswer("Follow-up question", List.of(), conversationId);

    var messages = chatMemory.get(conversationId);
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
    assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    assertThat(messages.get(2).getMessageType()).isEqualTo(MessageType.USER);
    assertThat(messages.get(3).getMessageType()).isEqualTo(MessageType.ASSISTANT);
  }

  @Test
  void secondCallPromptContainsHistoryWithSystemFirst() {
    var chatResponse1 =
        new ChatResponse(List.of(new Generation(new AssistantMessage("First answer"))));
    var chatResponse2 =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Second answer"))));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    when(chatModel.call(captor.capture())).thenReturn(chatResponse1, chatResponse2);

    answerGenerationService.generateAnswer("First question", List.of(), "conv-order");
    answerGenerationService.generateAnswer("Follow-up", List.of(), "conv-order");

    Prompt secondPrompt = captor.getAllValues().get(1);
    var messages = secondPrompt.getInstructions();

    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
    assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.USER);
    assertThat(messages.get(1).getText()).isEqualTo("First question");
    assertThat(messages.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    assertThat(messages.get(3).getMessageType()).isEqualTo(MessageType.USER);
    assertThat(messages.get(3).getText()).isEqualTo("Follow-up");
  }

  @Test
  void memoryStoresOnlyPlainQuestionsNotRagContext() {
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    var chunk = new Document("Some RAG content", Map.of("file_name", "doc.md"));
    answerGenerationService.generateAnswer("My question", List.of(chunk), "conv-rag");

    var messages = chatMemory.get("conv-rag");
    assertThat(messages.get(0).getText()).isEqualTo("My question");
    assertThat(messages.get(0).getText()).doesNotContain("RAG content");
  }
}
