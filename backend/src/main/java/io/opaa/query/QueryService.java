package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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
  private final CitationParser citationParser;

  public QueryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      CitationParser citationParser) {
    this.vectorStore = vectorStore;
    this.answerGenerationService = answerGenerationService;
    this.citationParser = citationParser;
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

    String rawAnswer = extractAnswer(chatResponse);
    Set<String> citedDocumentIds = citationParser.extractCitedDocumentIds(rawAnswer);
    String cleanAnswer = citationParser.removeCitations(rawAnswer);
    List<SourceReference> sources = mapSources(relevantChunks, citedDocumentIds);

    log.debug(
        "Citations found: {} cited, {} total sources", citedDocumentIds.size(), sources.size());

    long durationMs = System.currentTimeMillis() - startTime;
    String model = extractModel(chatResponse);
    int tokenCount = extractTokenCount(chatResponse);

    return new QueryResponse(
        cleanAnswer, sources, new QueryMetadata(model, tokenCount, durationMs));
  }

  private List<SourceReference> mapSources(List<Document> chunks, Set<String> citedDocumentIds) {
    return chunks.stream()
        .map(
            chunk -> {
              String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
              String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
              double score = chunk.getScore() != null ? chunk.getScore() : 0.0;
              String excerpt = truncateExcerpt(chunk.getText(), 200);
              boolean cited = citedDocumentIds.contains(documentId);
              return new SourceReference(fileName, score, excerpt, cited);
            })
        .collect(
            toMap(
                SourceReference::fileName,
                source -> source,
                (a, b) -> {
                  boolean eithCited = a.cited() || b.cited();
                  SourceReference winner = a.relevanceScore() >= b.relevanceScore() ? a : b;
                  if (eithCited && !winner.cited()) {
                    return new SourceReference(
                        winner.fileName(), winner.relevanceScore(), winner.excerpt(), true);
                  }
                  return winner;
                },
                LinkedHashMap::new))
        .values()
        .stream()
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
