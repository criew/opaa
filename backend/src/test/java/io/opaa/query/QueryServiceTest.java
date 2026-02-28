package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.QueryResponse;
import io.opaa.indexing.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
  @Mock private DocumentRepository documentRepository;
  private QueryService queryService;

  @BeforeEach
  void setUp() {
    queryService =
        new QueryService(
            vectorStore,
            answerGenerationService,
            chatMemory,
            new CitationParser(),
            documentRepository);
  }

  @Test
  void queryMarksCitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var usage = createUsage(100, 200);
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var answer = "The answer is 42 【source: doc-123#0 | readme.md】";
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    when(answerGenerationService.generateAnswer(eq("What?"), any(), any()))
        .thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null);

    assertThat(response.answer()).contains("【source:");
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().fileName()).isEqualTo("readme.md");
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.85);
    assertThat(response.sources().getFirst().cited()).isTrue();
    assertThat(response.sources().getFirst().matchCount()).isEqualTo(1);
    assertThat(response.metadata().model()).isEqualTo("gpt-4o");
    assertThat(response.metadata().tokenCount()).isEqualTo(300);
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
  void queryMarksUncitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var uncitedChunk =
        Document.builder()
            .text("Uncited content")
            .metadata(Map.of("file_name", "other.pdf", "document_id", "doc-2"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, uncitedChunk));

    var answer = "Info from readme 【source: doc-1#0 | readme.md】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(2);
    assertThat(response.sources().get(0).cited()).isTrue();
    assertThat(response.sources().get(1).cited()).isFalse();
  }

  @Test
  void queryCountsMatchesPerFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("First chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Second chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var chunk3 =
        Document.builder()
            .text("Readme chunk")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-2"))
            .score(0.8)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2, chunk3));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(2);
    assertThat(response.sources().get(0).fileName()).isEqualTo("report.pdf");
    assertThat(response.sources().get(0).matchCount()).isEqualTo(2);
    assertThat(response.sources().get(1).fileName()).isEqualTo("readme.md");
    assertThat(response.sources().get(1).matchCount()).isEqualTo(1);
  }

  @Test
  void queryRetainsCitationMarkersInAnswer() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer 【source: doc-1#0 | readme.md】 is here.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.answer()).isEqualTo(answer);
  }

  @Test
  void queryDeduplicatesSourcesByFileName() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("High relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Lower relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.9);
  }

  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No results"))));
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
  void queryPreservesCitedFlagWhenDeduplicatingChunksFromSameFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var higherScoreUncitedChunk =
        Document.builder()
            .text("Higher score uncited")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-2"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, higherScoreUncitedChunk));

    var answer = "Info 【source: doc-1#0 | report.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null);

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().cited()).isTrue();
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.95);
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
  void queryEnrichesThirdMessageWithFirstUserQuestion() {
    when(chatMemory.get("conv-third"))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR."),
                new UserMessage("Mach daraus eine Tabelle"),
                new AssistantMessage("Hier ist die Tabelle...")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Sortiert"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Sortiere nach Datum", "conv-third");

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Sortiere nach Datum");
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

  @Test
  void validateConversationIdRejectsToolLongId() {
    String tooLong = "a".repeat(51);
    assertThatThrownBy(() -> queryService.validateConversationId(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid conversationId format");
  }

  @Test
  void validateConversationIdRejectsSpecialCharacters() {
    assertThatThrownBy(() -> queryService.validateConversationId("id with spaces"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queryService.validateConversationId("id;DROP TABLE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queryService.validateConversationId("<script>alert(1)</script>"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queryService.validateConversationId("id/path/../traversal"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validateConversationIdAcceptsValidFormats() {
    assertThat(queryService.validateConversationId("abc-123")).isEqualTo("abc-123");
    assertThat(queryService.validateConversationId("A")).isEqualTo("A");
    assertThat(queryService.validateConversationId("a".repeat(50))).hasSize(50);
  }

  @Test
  void validateConversationIdGeneratesUuidForNullOrBlank() {
    assertThat(queryService.validateConversationId(null)).isNotBlank();
    assertThat(queryService.validateConversationId("")).isNotBlank();
    assertThat(queryService.validateConversationId("   ")).isNotBlank();
  }

  private Usage createUsage(int promptTokens, int completionTokens) {
    return new Usage() {
      @Override
      public Integer getPromptTokens() {
        return promptTokens;
      }

      @Override
      public Integer getCompletionTokens() {
        return completionTokens;
      }

      @Override
      public Object getNativeUsage() {
        return null;
      }
    };
  }
}
