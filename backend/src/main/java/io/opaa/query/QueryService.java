package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import io.opaa.indexing.DocumentRepository;
import io.opaa.observability.QueryMetrics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.annotation.Transactional;

public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private static final Pattern VALID_CONVERSATION_ID = Pattern.compile("^[a-zA-Z0-9-]{1,50}$");

  private final VectorStore vectorStore;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final DocumentRepository documentRepository;
  private final QueryMetrics metrics;
  private final QueryProperties queryProperties;

  public QueryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      DocumentRepository documentRepository,
      QueryMetrics metrics,
      QueryProperties queryProperties) {
    this.vectorStore = vectorStore;
    this.answerGenerationService = answerGenerationService;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.documentRepository = documentRepository;
    this.metrics = metrics;
    this.queryProperties = queryProperties;
  }

  @Transactional(readOnly = true)
  public QueryResponse query(String question, String conversationId) {
    return metrics
        .queryTimer()
        .record(
            () -> {
              try {
                String effectiveConversationId = validateConversationId(conversationId);

                String searchQuery = buildSearchQuery(question, effectiveConversationId);

                long startTime = System.currentTimeMillis();

                List<Document> relevantChunks =
                    vectorStore.similaritySearch(
                        SearchRequest.builder()
                            .query(searchQuery)
                            .topK(queryProperties.topK())
                            .similarityThreshold(queryProperties.similarityThreshold())
                            .build());

                log.debug("Found {} relevant chunks for query", relevantChunks.size());

                ChatResponse chatResponse =
                    answerGenerationService.generateAnswer(
                        question, relevantChunks, effectiveConversationId);

                String answer = extractAnswer(chatResponse);
                Set<String> citedDocumentIds = citationParser.extractCitedDocumentIds(answer);
                Map<String, Integer> matchCounts = countMatchesPerFile(relevantChunks);
                Map<String, Instant> indexedAtByDocId = lookupIndexedAt(relevantChunks);
                List<SourceReference> sources =
                    mapSources(relevantChunks, citedDocumentIds, matchCounts, indexedAtByDocId);

                log.debug(
                    "Citations found: {} cited, {} total sources",
                    citedDocumentIds.size(),
                    sources.size());

                long durationMs = System.currentTimeMillis() - startTime;
                String model = extractModel(chatResponse);
                int tokenCount = extractTokenCount(chatResponse);

                metrics.recordSuccess(tokenCount);

                return new QueryResponse(
                    answer,
                    sources,
                    new QueryMetadata(model, tokenCount, durationMs),
                    effectiveConversationId);
              } catch (RuntimeException e) {
                metrics.recordError();
                throw e;
              }
            });
  }

  private Map<String, Integer> countMatchesPerFile(List<Document> chunks) {
    return chunks.stream()
        .collect(
            Collectors.groupingBy(
                chunk -> chunk.getMetadata().getOrDefault("file_name", "unknown").toString(),
                Collectors.summingInt(e -> 1)));
  }

  private Map<String, Instant> lookupIndexedAt(List<Document> chunks) {
    Set<String> documentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());

    Map<String, Instant> result = new LinkedHashMap<>();
    for (String docId : documentIds) {
      try {
        documentRepository
            .findById(UUID.fromString(docId))
            .ifPresent(doc -> result.put(docId, doc.getIndexedAt()));
      } catch (IllegalArgumentException e) {
        log.debug("Invalid document ID format: {}", docId);
      }
    }
    return result;
  }

  private List<SourceReference> mapSources(
      List<Document> chunks,
      Set<String> citedDocumentIds,
      Map<String, Integer> matchCounts,
      Map<String, Instant> indexedAtByDocId) {
    return chunks.stream()
        .map(
            chunk -> {
              String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
              String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
              double score = chunk.getScore() != null ? chunk.getScore() : 0.0;
              boolean cited = citedDocumentIds.contains(documentId);
              int matches = matchCounts.getOrDefault(fileName, 1);
              Instant indexedAt = indexedAtByDocId.get(documentId);
              return new SourceReference(fileName, score, matches, indexedAt, cited);
            })
        .collect(
            toMap(
                SourceReference::fileName,
                source -> source,
                QueryService::mergeSourceReferences,
                LinkedHashMap::new))
        .values()
        .stream()
        .toList();
  }

  /**
   * Merges duplicate source references for the same file, keeping the one with the highest
   * relevance score while preserving citation status. If either reference was cited in the answer,
   * the merged result is marked as cited — because any chunk from that document being cited means
   * the document as a whole contributed to the answer.
   */
  static SourceReference mergeSourceReferences(SourceReference a, SourceReference b) {
    SourceReference preferred = a.relevanceScore() >= b.relevanceScore() ? a : b;
    boolean shouldBeCited = a.cited() || b.cited();

    if (shouldBeCited && !preferred.cited()) {
      return new SourceReference(
          preferred.fileName(),
          preferred.relevanceScore(),
          preferred.matchCount(),
          preferred.indexedAt(),
          true);
    }

    return preferred;
  }

  private String extractAnswer(ChatResponse response) {
    if (response.getResult() == null || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text != null ? text : "";
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

  String validateConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    if (!VALID_CONVERSATION_ID.matcher(conversationId).matches()) {
      throw new IllegalArgumentException("Invalid conversationId format");
    }
    return conversationId;
  }

  String buildSearchQuery(String question, String conversationId) {
    List<Message> history = chatMemory.get(conversationId);
    if (history.isEmpty()) {
      return question;
    }

    String firstUserMessage = null;
    for (Message message : history) {
      if (message.getMessageType() == MessageType.USER) {
        firstUserMessage = message.getText();
        break;
      }
    }

    if (firstUserMessage == null) {
      return question;
    }

    log.debug(
        "Enriching search query with conversation context: '{}' -> '{} {}'",
        question,
        firstUserMessage,
        question);
    return firstUserMessage + " " + question;
  }
}
