package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.llm.ActiveChatModelResolver;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class AnswerGenerationServiceTest {

  @Mock private ChatModel chatModel;
  @Mock private ActiveChatModelResolver activeChatModelResolver;

  private ChatMemory chatMemory;
  private AnswerGenerationService answerGenerationService;

  @BeforeEach
  void setUp() {
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    chatMemory = MessageWindowChatMemory.builder().build();
    lenient()
        .when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    answerGenerationService = new AnswerGenerationService(activeChatModelResolver, chatMemory);
  }

  /**
   * #1487: the answer sees <b>every</b> note point, including the ANTWORTFORM ones the sub-question
   * decomposition never gets - a Darstellungswunsch is noise for the search and the whole point
   * here.
   */
  @Test
  void everyNotePointReachesTheAnswerPrompt() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort")))));

    answerGenerationService.generateAnswer(
        "Was kostet der Ausweis?",
        List.of(),
        "conv-note",
        List.of("Bezugsjahr 2024", "Möchte knappe Antworten"));

    String systemText = systemTextOf(capturedPrompt());
    assertThat(systemText)
        .contains("Gesprächsnotiz")
        .contains("- Bezugsjahr 2024")
        .contains("- Möchte knappe Antworten");
    // Ahead of the passages: under "Context documents:" the note would stand inside the
    // section the "Only cite documents listed below" rule governs - and in a chat without a
    // knowledge base it would be the only thing there.
    assertThat(systemText.indexOf("Gesprächsnotiz"))
        .isLessThan(systemText.indexOf("Context documents:"));
  }

  @Test
  void withoutANotePointTheAnswerPromptCarriesNoNoteBlock() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort")))));

    answerGenerationService.generateAnswer(
        "Was kostet der Ausweis?", List.of(), "conv-no-note", List.of());

    assertThat(systemTextOf(capturedPrompt())).doesNotContain("Gesprächsnotiz");
  }

  private Prompt capturedPrompt() {
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    return promptCaptor.getValue();
  }

  private static String systemTextOf(Prompt prompt) {
    return prompt.getInstructions().stream()
        .filter(message -> message.getMessageType() == MessageType.SYSTEM)
        .map(Message::getText)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void generateAnswerBuildsCorrectPromptAndReturnsResponse() {
    var chunk1 =
        new Document("Chunk one text", Map.of("file_name", "doc1.md", "document_id", "id-1"));
    var chunk2 =
        new Document("Chunk two text", Map.of("file_name", "doc2.pdf", "document_id", "id-2"));

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Generated answer"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result =
        answerGenerationService.generateAnswer(
            "What is OPAA?", List.of(chunk1, chunk2), "conv-123", List.of());

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
  void generateAnswerIncludesCitationInstructionsInSystemPrompt() {
    var chunk =
        new Document(
            "Content", Map.of("file_name", "readme.md", "document_id", "uuid-1", "chunk_index", 0));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    answerGenerationService.generateAnswer("Question?", List.of(chunk), "conv-citation", List.of());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());

    String systemText = promptCaptor.getValue().getInstructions().get(0).getText();
    assertThat(systemText).contains("【source:");
    assertThat(systemText).contains("CITATION RULES");
    assertThat(systemText).contains("cite as: 【source: uuid-1#0 | readme.md】");
  }

  @Test
  void generateAnswerHandlesEmptyChunks() {
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No context available"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    ChatResponse result =
        answerGenerationService.generateAnswer("Question?", List.of(), "conv-456", List.of());

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

    answerGenerationService.generateAnswer("First question", List.of(), conversationId, List.of());
    answerGenerationService.generateAnswer(
        "Follow-up question", List.of(), conversationId, List.of());

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

    answerGenerationService.generateAnswer("First question", List.of(), "conv-order", List.of());
    answerGenerationService.generateAnswer("Follow-up", List.of(), "conv-order", List.of());

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
    answerGenerationService.generateAnswer("My question", List.of(chunk), "conv-rag", List.of());

    var messages = chatMemory.get("conv-rag");
    assertThat(messages.get(0).getText()).isEqualTo("My question");
    assertThat(messages.get(0).getText()).doesNotContain("RAG content");
  }

  /**
   * #1486: the conversation window holds the answer without its citation markers, while the
   * response the caller persists keeps them - the persisted text is the truth for footnotes, text
   * anchors and the evidence drawer.
   */
  @Test
  void theWindowLosesTheCitationMarkersWhileTheReturnedAnswerKeepsThem() {
    String answerWithCitation =
        "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. "
            + "【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | anwohnerparken.md】";
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage(answerWithCitation)))));

    ChatResponse response =
        answerGenerationService.generateAnswer(
            "Frage?", List.of(), "conv-citation-memory", List.of());

    assertThat(response.getResult().getOutput().getText()).isEqualTo(answerWithCitation);
    assertThat(chatMemory.get("conv-citation-memory"))
        .extracting(Message::getText)
        .containsExactly("Frage?", "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.");
  }

  /**
   * #1486: a marker of the previous turn must not reach the next prompt - that is the repetition
   * the removal exists for.
   */
  @Test
  void theFollowUpPromptCarriesNoMarkerOfThePreviousAnswer() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("Erste Antwort. 【source: doc-1#0 | a.md】")))),
            new ChatResponse(List.of(new Generation(new AssistantMessage("Zweite Antwort")))));
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);

    answerGenerationService.generateAnswer("Erste Frage", List.of(), "conv-no-marker", List.of());
    answerGenerationService.generateAnswer("Folgefrage", List.of(), "conv-no-marker", List.of());

    verify(chatModel, times(2)).call(captor.capture());
    assertThat(captor.getAllValues().get(1).getInstructions())
        .filteredOn(message -> message.getMessageType() == MessageType.ASSISTANT)
        .extracting(Message::getText)
        .containsExactly("Erste Antwort.");
  }

  /**
   * #1486: an answer that is nothing but a marker leaves no assistant message in the window at all.
   * An empty assistant message is rejected with 400 by some providers, and the reload path would
   * rebuild it from the persisted text on every following turn of that chat.
   */
  @Test
  void anAnswerThatIsOnlyAMarkerLeavesNoAssistantMessageInTheWindow() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("【source: doc-1#0 | a.md】")))));

    answerGenerationService.generateAnswer("Frage?", List.of(), "conv-only-marker", List.of());

    assertThat(chatMemory.get("conv-only-marker"))
        .extracting(Message::getText)
        .containsExactly("Frage?");
  }
}
