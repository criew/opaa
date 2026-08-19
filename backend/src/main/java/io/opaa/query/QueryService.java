package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.observability.QueryMetrics;
import java.time.Instant;
import java.util.HashSet;
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
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private static final Pattern VALID_CONVERSATION_ID = Pattern.compile("^[a-zA-Z0-9-]{1,50}$");
  private static final String LIBRARY_ID_METADATA_KEY = "library_id";

  private final VectorStore vectorStore;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final DocumentRepository documentRepository;
  private final UserRepository userRepository;
  private final LibraryAccessService libraryAccessService;
  private final PermissionHistoryService permissionHistoryService;
  private final QueryMetrics metrics;
  private final QueryProperties queryProperties;

  public QueryService(
      VectorStore vectorStore,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      DocumentRepository documentRepository,
      UserRepository userRepository,
      LibraryAccessService libraryAccessService,
      PermissionHistoryService permissionHistoryService,
      QueryMetrics metrics,
      QueryProperties queryProperties) {
    this.vectorStore = vectorStore;
    this.answerGenerationService = answerGenerationService;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.documentRepository = documentRepository;
    this.userRepository = userRepository;
    this.libraryAccessService = libraryAccessService;
    this.permissionHistoryService = permissionHistoryService;
    this.metrics = metrics;
    this.queryProperties = queryProperties;
  }

  /**
   * Answers {@code question}, restricted to chunks from libraries {@code currentUserId} may read
   * (#202 - the permission-aware vector search, the central gap the epic set out to close: before
   * this, the similarity search ran with no metadata filter whatsoever). The filter is part of the
   * {@link VectorStore#similaritySearch} call itself, not a post-filter applied to its result - an
   * unauthorized chunk is never loaded or ranked, let alone returned. There is no bypass for a
   * system admin here (unlike {@code LibraryAccessService#effectiveRole}, used for library
   * administration): a query always reads with the calling user's own rights, with no second rights
   * context (ADR-0008 §5).
   *
   * <p>An empty readable set short-circuits before the vector store is even called, skipping
   * straight to answer generation with zero chunks - the same code path a query with genuinely no
   * matching content takes, so the resulting message cannot be used to distinguish "no permission
   * on anything" from "nothing matched" (#202 acceptance criteria).
   *
   * <p><b>#238's regression check:</b> the readable set ({@code readableLibraryIds} below, distinct
   * from the narrower {@code searchScope} #526 may derive from it) is compared against {@link
   * PermissionHistoryService#readableLibraryIdsAsOf}'s reconstruction for the same instant, logging
   * a warning if the live computation reaches a library the history would not - a beweisbarer
   * Durchsetzungsfehler per
   * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten.
   * Deliberately not a per-query log line of the full permission set itself: the feature spec
   * rejects that as an unnecessary expansion of personal data (see the same section), so only a
   * detected mismatch - not every query - is written to the application log, and even then only the
   * offending library id, not the caller's whole readable set.
   *
   * <p><b>#526's search-scope controls</b>, {@code useKnowledge} and {@code requestedLibraryIds}:
   * {@code useKnowledge = true} preserves the behaviour above exactly - every library {@code
   * currentUserId} may read, {@code requestedLibraryIds} ignored. {@code useKnowledge = false}
   * narrows the scope to {@code requestedLibraryIds} intersected with the readable set - never
   * widened beyond it, matching #526's acceptance criteria that a referenced but unreadable library
   * yields no hits rather than being silently granted. An empty intersection in that mode also
   * takes the empty-scope short-circuit above and additionally marks {@link
   * QueryMetadata#getAnsweredWithoutKnowledge()} so the caller can distinguish "no knowledge base
   * searched" from "searched but found nothing".
   */
  @Transactional(readOnly = true)
  public QueryResponse query(
      String question,
      String conversationId,
      UUID currentUserId,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds) {
    return metrics
        .queryTimer()
        .record(
            () -> {
              try {
                User currentUser =
                    userRepository
                        .findById(currentUserId)
                        .orElseThrow(
                            () ->
                                new ResponseStatusException(
                                    HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));

                String effectiveConversationId = validateConversationId(conversationId);

                String searchQuery = buildSearchQuery(question, effectiveConversationId);

                long startTime = System.currentTimeMillis();

                Instant scopeComputedAt = Instant.now();
                Set<UUID> readableLibraryIds =
                    libraryAccessService.readableLibraryIds(
                        currentUserId, currentUser.getOrganizationId());
                checkAgainstPermissionHistory(
                    readableLibraryIds,
                    currentUserId,
                    currentUser.getOrganizationId(),
                    scopeComputedAt);

                Set<UUID> searchScope =
                    useKnowledge
                        ? readableLibraryIds
                        : intersectWithReadable(requestedLibraryIds, readableLibraryIds);
                boolean answeredWithoutKnowledge = !useKnowledge && searchScope.isEmpty();

                List<Document> relevantChunks =
                    searchScope.isEmpty()
                        ? List.of()
                        : vectorStore.similaritySearch(
                            SearchRequest.builder()
                                .query(searchQuery)
                                .topK(queryProperties.topK())
                                .similarityThreshold(queryProperties.similarityThreshold())
                                .filterExpression(libraryFilter(searchScope))
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

                QueryMetadata metadata =
                    new QueryMetadata(model, tokenCount, durationMs)
                        .answeredWithoutKnowledge(answeredWithoutKnowledge);
                return new QueryResponse(answer, sources, metadata, effectiveConversationId);
              } catch (RuntimeException e) {
                metrics.recordError();
                throw e;
              }
            });
  }

  /**
   * {@code requestedLibraryIds ∩ readableLibraryIds} - the #526 search scope for {@code
   * useKnowledge = false}. Deliberately never adds anything beyond {@code readableLibraryIds}: a
   * reference to a library the caller cannot read is silently dropped, not honoured.
   */
  private Set<UUID> intersectWithReadable(
      List<UUID> requestedLibraryIds, Set<UUID> readableLibraryIds) {
    if (requestedLibraryIds == null || requestedLibraryIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> scope = new HashSet<>(requestedLibraryIds);
    scope.retainAll(readableLibraryIds);
    return scope;
  }

  /**
   * #238's regression check - see {@link #query}'s Javadoc. {@code readableScope} is the full set
   * {@link LibraryAccessService#readableLibraryIds} computed for this query at {@code asOf} - not
   * necessarily the narrower {@code searchScope} #526's {@code useKnowledge = false} may actually
   * hand to the vector store, since that mode can restrict the search to a subset of what is merely
   * readable. Any id in {@code readableScope} the permission history does not also grant as of
   * {@code asOf} is a mismatch, logged as a single warning per query (not once per offending
   * library - code review of #427, nit 2), never silently ignored. {@code asOf} is the instant
   * {@code readableScope} was itself computed at, not a fresh {@code Instant.now()} taken here -
   * reusing it avoids a false-positive mismatch from a permission change landing in the gap between
   * the two computations.
   */
  private void checkAgainstPermissionHistory(
      Set<UUID> readableScope, UUID currentUserId, UUID organizationId, Instant asOf) {
    if (readableScope.isEmpty()) {
      return;
    }
    Set<UUID> historized =
        permissionHistoryService.readableLibraryIdsAsOf(currentUserId, organizationId, asOf);
    Set<UUID> mismatched = new HashSet<>(readableScope);
    mismatched.removeAll(historized);
    if (!mismatched.isEmpty()) {
      log.warn(
          "Permission history regression check: user {} was granted {} librar{} as readable the"
              + " permission history does not confirm as of {} - possible enforcement drift"
              + " between the live and historized rights computation. This checks the full"
              + " readable set, not the (possibly narrower, #526 useKnowledge=false) scope"
              + " actually searched: {}",
          currentUserId,
          mismatched.size(),
          mismatched.size() == 1 ? "y" : "ies",
          asOf,
          mismatched);
    }
  }

  /**
   * Builds the {@code library_id IN (...)} filter passed straight into {@link
   * VectorStore#similaritySearch} - see {@link #query} for why this must be part of the search
   * call, never a filter applied to its result afterwards.
   */
  private Filter.Expression libraryFilter(Set<UUID> readableLibraryIds) {
    List<Object> libraryIdValues =
        readableLibraryIds.stream().map(UUID::toString).map(Object.class::cast).toList();
    return new FilterExpressionBuilder().in(LIBRARY_ID_METADATA_KEY, libraryIdValues).build();
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
              return new SourceReference(fileName, score, matches, cited).indexedAt(indexedAt);
            })
        .collect(
            toMap(
                SourceReference::getFileName,
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
    SourceReference preferred = a.getRelevanceScore() >= b.getRelevanceScore() ? a : b;
    boolean shouldBeCited = a.getCited() || b.getCited();

    if (shouldBeCited && !preferred.getCited()) {
      return new SourceReference(
              preferred.getFileName(),
              preferred.getRelevanceScore(),
              preferred.getMatchCount(),
              true)
          .indexedAt(preferred.getIndexedAt());
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
      throw new IllegalArgumentException("Ungültiges Format der conversationId");
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
