package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.dto.QueryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class QueryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> "/tmp/opaa-test-docs");
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @MockitoBean private ChatModel chatModel;

  @Autowired private VectorStore vectorStore;
  @Autowired private QueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
  }

  @Test
  void endToEndQueryReturnsAnswerWithSources() {
    // Index some test documents into the vector store
    var doc1 =
        new Document(
            "OPAA is an AI-powered project assistant built with Spring Boot.",
            Map.of("file_name", "readme.md", "document_id", "doc-1", "chunk_index", 0));
    var doc2 =
        new Document(
            "The deployment uses Docker Compose with PostgreSQL and pgvector.",
            Map.of("file_name", "deployment.md", "document_id", "doc-2", "chunk_index", 0));
    vectorStore.add(List.of(doc1, doc2));

    // Mock the ChatModel response
    var usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 150;
          }

          @Override
          public Integer getCompletionTokens() {
            return 100;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var assistantMessage = new AssistantMessage("OPAA is an AI project assistant (readme.md).");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)), metadata);
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    // Execute the query
    QueryResponse response = queryService.query("What is OPAA?", null);

    // Verify the response
    assertThat(response.answer()).isEqualTo("OPAA is an AI project assistant (readme.md).");
    assertThat(response.sources()).isNotEmpty();
    assertThat(response.sources()).allMatch(s -> s.fileName() != null);
    assertThat(response.metadata().model()).isEqualTo("gpt-4o");
    assertThat(response.metadata().tokenCount()).isEqualTo(250);
    assertThat(response.metadata().durationMs()).isGreaterThan(0);
  }

  @Test
  void queryWithNoMatchingDocumentsReturnsEmptySources() {
    // No documents in vector store — similarity search returns empty
    var assistantMessage =
        new AssistantMessage("I don't have enough context to answer that question.");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Something completely unrelated", null);

    assertThat(response.answer()).contains("don't have enough context");
    assertThat(response.sources()).isEmpty();
  }

  @Test
  void queryRejectsInvalidConversationId() {
    assertThatThrownBy(() -> queryService.query("Test question", "<script>alert(1)</script>"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid conversationId format");
  }
}
