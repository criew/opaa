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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
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
  @Spy private CitationParser citationParser = new CitationParser();
  @InjectMocks private QueryService queryService;

  @Test
  void queryMarksCitedSourcesCorrectly() {
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var usage = createUsage(100, 200);
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var answer = "The answer is 42 【source: doc-123 | readme.md】";
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    when(answerGenerationService.generateAnswer(eq("What?"), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?");

    assertThat(response.answer()).doesNotContain("【source:");
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().fileName()).isEqualTo("readme.md");
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.85);
    assertThat(response.sources().getFirst().cited()).isTrue();
    assertThat(response.metadata().model()).isEqualTo("gpt-4o");
    assertThat(response.metadata().tokenCount()).isEqualTo(300);
  }

  @Test
  void queryMarksUncitedSourcesCorrectly() {
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

    var answer = "Info from readme 【source: doc-1 | readme.md】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources()).hasSize(2);
    assertThat(response.sources().get(0).fileName()).isEqualTo("readme.md");
    assertThat(response.sources().get(0).cited()).isTrue();
    assertThat(response.sources().get(1).fileName()).isEqualTo("other.pdf");
    assertThat(response.sources().get(1).cited()).isFalse();
  }

  @Test
  void queryTruncatesLongExcerpts() {
    String longText = "A".repeat(300);
    var chunk =
        Document.builder()
            .text(longText)
            .metadata(Map.of("file_name", "long.md", "document_id", "doc-1"))
            .score(0.9)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources().getFirst().excerpt()).hasSize(203);
    assertThat(response.sources().getFirst().excerpt()).endsWith("...");
  }

  @Test
  void queryDeduplicatesSourcesByFileName() {
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
    var chunk3 =
        Document.builder()
            .text("Readme content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-2"))
            .score(0.8)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2, chunk3));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources()).hasSize(2);
    assertThat(response.sources().get(0).fileName()).isEqualTo("report.pdf");
    assertThat(response.sources().get(0).relevanceScore()).isEqualTo(0.9);
    assertThat(response.sources().get(1).fileName()).isEqualTo("readme.md");
  }

  @Test
  void queryKeepsHighestScoreRegardlessOfOrder() {
    var lowScoreFirst =
        Document.builder()
            .text("Low score")
            .metadata(Map.of("file_name", "data.csv", "document_id", "doc-1"))
            .score(0.6)
            .build();
    var highScoreSecond =
        Document.builder()
            .text("High score")
            .metadata(Map.of("file_name", "data.csv", "document_id", "doc-1"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(lowScoreFirst, highScoreSecond));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.95);
  }

  @Test
  void queryUsesExcerptFromHighestScoringChunk() {
    var lowScore =
        Document.builder()
            .text("Wrong excerpt")
            .metadata(Map.of("file_name", "notes.txt", "document_id", "doc-1"))
            .score(0.5)
            .build();
    var highScore =
        Document.builder()
            .text("Correct excerpt")
            .metadata(Map.of("file_name", "notes.txt", "document_id", "doc-1"))
            .score(0.99)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(lowScore, highScore));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().excerpt()).isEqualTo("Correct excerpt");
  }

  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No results"))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    queryService.query("Test query");

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getQuery()).isEqualTo("Test query");
    assertThat(request.getTopK()).isEqualTo(5);
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
  }

  @Test
  void queryPreservesCitedFlagWhenDeduplicatingChunksFromSameFile() {
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

    var answer = "Info 【source: doc-1 | report.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().cited()).isTrue();
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.95);
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
