package io.opaa.query;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private static final int DEFAULT_TOP_K = 5;
  private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

  private final VectorStore vectorStore;
  private final AnswerGenerationService answerGenerationService;

  public QueryService(VectorStore vectorStore, AnswerGenerationService answerGenerationService) {
    this.vectorStore = vectorStore;
    this.answerGenerationService = answerGenerationService;
  }

  public QueryResponse query(String question) {
    long startTime = System.currentTimeMillis();

    List<Document> relevantChunks =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(DEFAULT_TOP_K)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                .build());

    log.debug("Found {} relevant chunks for query", relevantChunks.size());

    ChatResponse chatResponse = answerGenerationService.generateAnswer(question, relevantChunks);

    String answer = extractAnswer(chatResponse);
    List<SourceReference> sources = mapSources(relevantChunks);

    long durationMs = System.currentTimeMillis() - startTime;
    String model = extractModel(chatResponse);
    int tokenCount = extractTokenCount(chatResponse);

    return new QueryResponse(answer, sources, new QueryMetadata(model, tokenCount, durationMs));
  }

  private List<SourceReference> mapSources(List<Document> chunks) {
    return chunks.stream()
        .map(
            chunk -> {
              String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
              double score = chunk.getScore() != null ? chunk.getScore() : 0.0;
              String excerpt = truncateExcerpt(chunk.getText(), 200);
              return new SourceReference(fileName, score, excerpt);
            })
        .toList();
  }

  private String extractAnswer(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text != null ? text : "";
  }

  private String truncateExcerpt(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength) + "...";
  }

  private String extractModel(ChatResponse response) {
    if (response.getMetadata() != null && response.getMetadata().getModel() != null) {
      return response.getMetadata().getModel();
    }
    return "unknown";
  }

  private int extractTokenCount(ChatResponse response) {
    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
      return response.getMetadata().getUsage().getTotalTokens();
    }
    return 0;
  }
}
