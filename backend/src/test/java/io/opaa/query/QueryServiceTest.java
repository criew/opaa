package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.QueryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

  @Mock private VectorStore vectorStore;
  @Mock private AnswerGenerationService answerGenerationService;
  @Mock private ChatMemory chatMemory;
  @InjectMocks private QueryService queryService;

  @Test
  void queryPerformsSimilaritySearchAndReturnsResponse() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "123"))
            .score(0.85)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 100;
          }

          @Override
          public Integer getCompletionTokens() {
            return 200;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var assistantMessage = new AssistantMessage("The answer is 42");
    var generation = new Generation(assistantMessage);
    var chatResponse = new ChatResponse(List.of(generation), metadata);
    when(answerGenerationService.generateAnswer(eq("What?"), any(), any()))
        .thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null);

    assertThat(response.answer()).isEqualTo("The answer is 42");
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().fileName()).isEqualTo("readme.md");
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.85);
    assertThat(response.sources().getFirst().excerpt()).isEqualTo("Relevant content");
    assertThat(response.metadata().model()).isEqualTo("gpt-4o");
    assertThat(response.metadata().tokenCount()).isEqualTo(300);
    assertThat(response.metadata().durationMs()).isGreaterThanOrEqualTo(0);
    assertThat(response.conversationId()).isNotNull().isNotBlank();
  }

  @Test
  void queryGeneratesConversationIdWhenNull() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.conversationId()).isNotNull().isNotBlank();
  }

  @Test
  void queryUsesProvidedConversationId() {
    when(chatMemory.get("existing-conv-id")).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), eq("existing-conv-id")))
        .thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", "existing-conv-id");

    assertThat(response.conversationId()).isEqualTo("existing-conv-id");
  }

  @Test
  void queryTruncatesLongExcerpts() {
    when(chatMemory.get(any())).thenReturn(List.of());
    String longText = "A".repeat(300);
    var chunk =
        Document.builder()
            .text(longText)
            .metadata(Map.of("file_name", "long.md"))
            .score(0.9)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var assistantMessage = new AssistantMessage("Answer");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources().getFirst().excerpt()).hasSize(203); // 200 + "..."
    assertThat(response.sources().getFirst().excerpt()).endsWith("...");
  }

  @Test
  void queryDeduplicatesSourcesByFileName() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("High relevance chunk")
            .metadata(Map.of("file_name", "report.pdf"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Lower relevance chunk")
            .metadata(Map.of("file_name", "report.pdf"))
            .score(0.7)
            .build();
    var chunk3 =
        Document.builder()
            .text("Readme content")
            .metadata(Map.of("file_name", "readme.md"))
            .score(0.8)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2, chunk3));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(2);
    assertThat(response.sources().get(0).fileName()).isEqualTo("report.pdf");
    assertThat(response.sources().get(0).relevanceScore()).isEqualTo(0.9);
    assertThat(response.sources().get(1).fileName()).isEqualTo("readme.md");
  }

  @Test
  void queryKeepsHighestScoreRegardlessOfOrder() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var lowScoreFirst =
        Document.builder()
            .text("Low score")
            .metadata(Map.of("file_name", "data.csv"))
            .score(0.6)
            .build();
    var highScoreSecond =
        Document.builder()
            .text("High score")
            .metadata(Map.of("file_name", "data.csv"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(lowScoreFirst, highScoreSecond));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.95);
  }

  @Test
  void queryUsesExcerptFromHighestScoringChunk() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var lowScore =
        Document.builder()
            .text("Wrong excerpt")
            .metadata(Map.of("file_name", "notes.txt"))
            .score(0.5)
            .build();
    var highScore =
        Document.builder()
            .text("Correct excerpt")
            .metadata(Map.of("file_name", "notes.txt"))
            .score(0.99)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(lowScore, highScore));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().excerpt()).isEqualTo("Correct excerpt");
  }

  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var assistantMessage = new AssistantMessage("No results");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Test query", null);

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getQuery()).isEqualTo("Test query");
    assertThat(request.getTopK()).isEqualTo(5);
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
  }

  @Test
  void queryEnrichesSearchWithConversationHistory() {
    when(chatMemory.get("conv-enrich"))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR.")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Tabelle"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Mach daraus eine tabellarische Auflistung", "conv-enrich");

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Mach daraus eine tabellarische Auflistung");
  }

  @Test
  void queryUsesPlainQuestionWhenNoHistory() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("First question", null);

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery()).isEqualTo("First question");
  }
}
