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
  @InjectMocks private QueryService queryService;

  @Test
  void queryPerformsSimilaritySearchAndReturnsResponse() {
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
    when(answerGenerationService.generateAnswer(eq("What?"), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?");

    assertThat(response.answer()).isEqualTo("The answer is 42");
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().fileName()).isEqualTo("readme.md");
    assertThat(response.sources().getFirst().relevanceScore()).isEqualTo(0.85);
    assertThat(response.sources().getFirst().excerpt()).isEqualTo("Relevant content");
    assertThat(response.metadata().model()).isEqualTo("gpt-4o");
    assertThat(response.metadata().tokenCount()).isEqualTo(300);
    assertThat(response.metadata().durationMs()).isGreaterThan(0);
  }

  @Test
  void queryTruncatesLongExcerpts() {
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
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question");

    assertThat(response.sources().getFirst().excerpt()).hasSize(203); // 200 + "..."
    assertThat(response.sources().getFirst().excerpt()).endsWith("...");
  }

  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var assistantMessage = new AssistantMessage("No results");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(answerGenerationService.generateAnswer(any(), any())).thenReturn(chatResponse);

    queryService.query("Test query");

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getQuery()).isEqualTo("Test query");
    assertThat(request.getTopK()).isEqualTo(5);
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
  }
}
