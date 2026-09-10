package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.api.types.MetadataFilterMatch;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.metadata.CitationFieldValue;
import io.opaa.indexing.metadata.CitationMetadataReader;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataFilterValidator;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.llm.RerankModelRole;
import io.opaa.observability.QueryMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Answers one question end to end (docs/handbuch/suche.md): resolves the search scope, runs the
 * {@link RetrievalPipeline} over it, has the answer generated, validates the citations and builds
 * the source rows.
 *
 * <p>A query always reads with the calling user's own rights - no system-admin bypass, no second
 * rights context (ADR-0008 §5) - and the permission filter is part of every search query itself,
 * the {@link VectorStore#similaritySearch} call and the full-text query alike, never a post-filter,
 * so an unauthorized chunk is never loaded or ranked. An empty search scope short-circuits to
 * answer generation with zero chunks, the same path a genuinely empty result takes, so the answer
 * cannot distinguish "no permission on anything" from "nothing matched".
 */
@Service
public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private final RetrievalPipeline retrievalPipeline;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final DocumentRepository documentRepository;
  private final LibraryAccessService libraryAccessService;
  private final PermissionHistoryService permissionHistoryService;
  private final ChatService chatService;
  private final QueryMetrics metrics;
  private final QueryProperties queryProperties;
  private final KnowledgeLibraryRepository knowledgeLibraryRepository;
  private final RerankModelRole rerankModelRole;
  private final DocumentMetadataService documentMetadataService;
  private final MetadataFilterValidator metadataFilterValidator;
  private final CitationMetadataReader citationMetadataReader;

  public QueryService(
      RetrievalPipeline retrievalPipeline,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      CitationValidator citationValidator,
      DocumentRepository documentRepository,
      LibraryAccessService libraryAccessService,
      PermissionHistoryService permissionHistoryService,
      ChatService chatService,
      QueryMetrics metrics,
      QueryProperties queryProperties,
      KnowledgeLibraryRepository knowledgeLibraryRepository,
      RerankModelRole rerankModelRole,
      DocumentMetadataService documentMetadataService,
      MetadataFilterValidator metadataFilterValidator,
      CitationMetadataReader citationMetadataReader) {
    this.metadataFilterValidator = metadataFilterValidator;
    this.citationMetadataReader = citationMetadataReader;
    this.retrievalPipeline = retrievalPipeline;
    this.answerGenerationService = answerGenerationService;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.documentRepository = documentRepository;
    this.libraryAccessService = libraryAccessService;
    this.permissionHistoryService = permissionHistoryService;
    this.chatService = chatService;
    this.metrics = metrics;
    this.queryProperties = queryProperties;
    this.knowledgeLibraryRepository = knowledgeLibraryRepository;
    this.rerankModelRole = rerankModelRole;
    this.documentMetadataService = documentMetadataService;
  }

  /**
   * Answers {@code question} against the libraries {@code caller} may read.
   *
   * <p>A chat {@code chatId} names and {@code caller} authored governs the turn entirely: search
   * scope and filter come from the chat's own settings - {@code useKnowledge} and {@code
   * requestedLibraryIds} are then ignored, not merely defaulted - the conversation memory is seeded
   * from its persisted history, and question and answer are persisted. Otherwise the query runs
   * ephemerally, with {@code useKnowledge = true} searching every readable library and {@code
   * false} narrowing to {@code requestedLibraryIds} intersected with the readable set - never
   * widened beyond it, so a referenced but unreadable library yields no hits.
   *
   * <p>Deliberately <em>not</em> {@code @Transactional}: an ambient transaction would hold one JDBC
   * connection for the whole call, LLM round trip included, while the write phase afterwards needs
   * a second one - under concurrent traffic that exhausts the pool into a deadlock rather than mere
   * contention. Every repository call below is therefore independently transactional and releases
   * its connection immediately.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds) {
    return query(question, chatId, caller, useKnowledge, requestedLibraryIds, MetadataFilter.NONE);
  }

  /**
   * The same query with a core-field filter. Like the search scope, the filter of a persisted chat
   * governs entirely - the chat's sticky filter is the Kontext der Unterhaltung - and only an
   * ephemeral query takes {@code requestedMetadataFilter}. The effective filter is validated
   * against the Dokumentart vocabulary (400 for an unknown code) and applied inside both search
   * paths, subordinate to the permission filter; every returned source says whether it matched or
   * was kept as "ohne Angabe".
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter) {
    UUID currentUserId = caller.id();
    return metrics
        .queryTimer()
        .record(
            () -> {
              try {
                // --- Read phase: membership/archive/scope checks and the retrieval below all
                // run without any ambient transaction of their own - each repository call opens
                // and releases its own short-lived connection.
                Optional<Chat> chat = chatService.findOwnedChat(chatId, currentUserId);
                // Querying is chatting: requires space membership even for an author who
                // already owns the chat - see ChatService#requireStillSpaceMember.
                chat.ifPresent(chatService::requireStillSpaceMember);
                // An archived space accepts no new content - checked before the LLM call, so
                // no answer is paid for that appendTurn would discard. appendTurn's own call to
                // the same guard remains the race guard for a space archived after this point.
                chat.ifPresent(c -> chatService.requireSpaceNotArchived(c.getSpaceId()));
                // A chatId that does not resolve to an owned persisted chat (including "none
                // given") runs ephemerally rather than being rejected, reused as the in-memory
                // conversation-cache key when the caller supplied one, or freshly generated
                // otherwise. Always qualified with currentUserId: without this, an unresolved
                // chatId (unknown, or another user's real chat id) would collide with the cache
                // key that chat's owner's own persisted-chat path uses, leaking one user's
                // conversation history into another's prompt.
                UUID effectiveChatId =
                    chat.map(Chat::getId)
                        .orElseGet(() -> chatId != null ? chatId : UUID.randomUUID());
                String conversationKey = currentUserId + ":" + effectiveChatId;
                seedConversationMemoryFromPersistedHistory(chat, conversationKey);

                // Before the decomposition call in the non-empty-scope branch below, so
                // durationMs includes its latency rather than silently excluding it.
                long startTime = System.currentTimeMillis();

                Instant scopeComputedAt = Instant.now();
                Set<UUID> readableLibraryIds =
                    libraryAccessService.readableLibraryIds(currentUserId, caller.organizationId());
                maybeCheckAgainstPermissionHistory(
                    readableLibraryIds, currentUserId, caller.organizationId(), scopeComputedAt);

                // A persisted chat's own settings govern the scope entirely; only an ephemeral
                // query falls back to the request-level useKnowledge/requestedLibraryIds.
                Set<UUID> searchScope =
                    resolveSearchScope(chat, useKnowledge, requestedLibraryIds, readableLibraryIds);
                MetadataFilter metadataFilter =
                    validatedMetadataFilter(
                        readableLibraryIds,
                        chat.map(Chat::getMetadataFilter)
                            .orElse(
                                requestedMetadataFilter == null
                                    ? MetadataFilter.NONE
                                    : requestedMetadataFilter));
                boolean effectiveUseKnowledge = chat.map(Chat::isUseKnowledge).orElse(useKnowledge);
                boolean answeredWithoutKnowledge = !effectiveUseKnowledge && searchScope.isEmpty();
                // Distinct from answeredWithoutKnowledge above: the chat's space is curated
                // but none of its associated libraries are readable by this caller, so the scope
                // legitimately resolves to empty. Only meaningful for a persisted chat - an
                // ephemeral query's empty scope means the caller has no readable library at all.
                boolean noKnowledgeAvailableInSpace =
                    effectiveUseKnowledge
                        && searchScope.isEmpty()
                        && chat.map(c -> chatService.spaceHasLibraryAssociations(c.getSpaceId()))
                            .orElse(false);

                // The decomposition LLM call only runs once there is actually something to
                // search - an empty scope would otherwise pay for it and discard the result.
                List<Document> relevantChunks;
                if (searchScope.isEmpty()) {
                  relevantChunks = List.of();
                } else {
                  relevantChunks =
                      retrieveRelevantChunksInGivenScope(
                          question, chatMemory.get(conversationKey), searchScope, metadataFilter);
                }

                // --- LLM call: the slowest step, and the reason no phase of this method
                // carries a transaction.
                ChatResponse chatResponse =
                    answerGenerationService.generateAnswer(
                        question, relevantChunks, conversationKey);

                String answer = extractAnswer(chatResponse);
                List<CitationValidator.ValidatedCitation> validatedCitations =
                    citationValidator.validate(
                        citationParser.extractCitations(answer), relevantChunks, answer);
                logInvalidCitations(validatedCitations);
                Map<String, Integer> matchCounts = countMatchesPerDocument(relevantChunks);
                Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId =
                    lookupSourceDocuments(relevantChunks);
                Map<UUID, CoreMetadata> coreMetadataByDocId =
                    lookupCoreMetadata(sourceDocumentsByDocId);
                Map<UUID, List<CitationFieldValue>> citationFieldsByDocId =
                    lookupCitationFields(sourceDocumentsByDocId);
                List<ChatSource> sources =
                    mapSources(
                        relevantChunks,
                        validatedCitations,
                        matchCounts,
                        sourceDocumentsByDocId,
                        coreMetadataByDocId,
                        citationFieldsByDocId,
                        metadataFilter);

                log.debug(
                    "Citations found: {} validated, {} total sources",
                    validatedCitations.size(),
                    sources.size());

                long durationMs = System.currentTimeMillis() - startTime;
                String model = extractModel(chatResponse);
                int tokenCount = extractTokenCount(chatResponse);

                metrics.recordSuccess(tokenCount);

                // --- Write phase: the one place this method's result is persisted, in
                // ChatService#appendTurn's own transaction(s). appendTurn's return value, not the
                // `chat` instance loaded above, is the source of truth for the title - the
                // fallback title on a first turn, never the LLM-derived one, which is generated
                // asynchronously after this response is built.
                String chatTitle =
                    chat.map(c -> chatService.appendTurn(c, question, answer, sources))
                        .orElse(null);

                QueryOutcome metadata =
                    new QueryOutcome(model, tokenCount, durationMs)
                        .answeredWithoutKnowledge(answeredWithoutKnowledge)
                        .noKnowledgeAvailableInSpace(noKnowledgeAvailableInSpace)
                        .searchedLibraries(searchedLibraries(searchScope));
                return new QueryResult(answer, sources, metadata, effectiveChatId)
                    .chatTitle(chatTitle);
              } catch (RuntimeException e) {
                metrics.recordError();
                throw e;
              }
            });
  }

  /**
   * The search scope a question runs in - a persisted chat's own settings govern it entirely; only
   * an ephemeral query falls back to the request-level {@code useKnowledge}/{@code
   * requestedLibraryIds}. Never wider than {@code readableLibraryIds}. Public so the metadata
   * filter options are built over exactly the libraries the next question would search.
   */
  public Set<UUID> resolveSearchScope(
      Optional<Chat> chat,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      Set<UUID> readableLibraryIds) {
    return chat.map(c -> chatService.effectiveLibraryScope(c, readableLibraryIds))
        .orElseGet(
            () ->
                useKnowledge
                    ? readableLibraryIds
                    : intersectWithReadable(requestedLibraryIds, readableLibraryIds));
  }

  /**
   * The filter checked against the schema - an unknown Dokumentart code, an unknown library field
   * or a value outside a field's list is a caller error (400), never silently a filter that matches
   * nothing but the documents without a value.
   */
  private MetadataFilter validatedMetadataFilter(
      Set<UUID> readableLibraryIds, MetadataFilter filter) {
    if (filter.isEmpty()) {
      return filter;
    }
    return metadataFilterValidator.validate(filter, readableLibraryIds);
  }

  /**
   * {@code requestedLibraryIds ∩ readableLibraryIds} - the search scope of an ephemeral query with
   * {@code useKnowledge = false}. Never adds anything beyond {@code readableLibraryIds}: a
   * reference to a library the caller cannot read is dropped, not honoured. A persisted chat's
   * sticky references go through {@link ChatService#effectiveLibraryScope}, which applies the same
   * rule.
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
   * Seeds the in-memory conversation cache from the persisted chat history on a cache miss - the
   * mechanism that makes {@link SubQueryDecompositionStage} and {@link
   * AnswerGenerationService#generateAnswer} see the persisted history without either reading the
   * database directly. Only touches the cache when it is empty for this key: re-adding would
   * duplicate every message, and a warm cache is already authoritative for this process.
   */
  private void seedConversationMemoryFromPersistedHistory(
      Optional<Chat> chat, String conversationKey) {
    if (chat.isEmpty() || !chatMemory.get(conversationKey).isEmpty()) {
      return;
    }
    List<Message> persistedHistory = chatService.historyAsSpringAiMessages(chat.get().getId());
    if (!persistedHistory.isEmpty()) {
      chatMemory.add(conversationKey, persistedHistory);
    }
  }

  /**
   * Samples {@link #checkAgainstPermissionHistory} down to {@link
   * QueryProperties#permissionHistorySampleRate} of queries: {@code 1.0} checks every query, {@code
   * 0.0} none. The dice roll happens here rather than inside the check itself, which stays
   * deterministic and directly testable.
   */
  private void maybeCheckAgainstPermissionHistory(
      Set<UUID> readableScope, UUID currentUserId, UUID organizationId, Instant asOf) {
    if (ThreadLocalRandom.current().nextDouble() >= queryProperties.permissionHistorySampleRate()) {
      return;
    }
    checkAgainstPermissionHistory(readableScope, currentUserId, organizationId, asOf);
  }

  /**
   * Compares the live readable set against the permission history's reconstruction for the same
   * instant (docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten).
   * Any library the live computation grants and the history does not is an enforcement drift,
   * logged as one warning per query with the offending ids only, never the whole readable set.
   * {@code asOf} must be the instant {@code readableScope} was computed at: a fresh {@code
   * Instant.now()} here would report a permission change landing in between as a mismatch.
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
              + " readable set, not the (possibly narrower, #525/#526) scope actually searched:"
              + " {}",
          currentUserId,
          mismatched.size(),
          mismatched.size() == 1 ? "y" : "ies",
          asOf,
          mismatched);
    }
  }

  /**
   * The retrieval half of {@link #query}: runs the whole {@link RetrievalPipeline} and returns the
   * chunks in the order and count the answer prompt would be built from. {@code searchScope} is
   * taken as given - hence the name - so the caller is responsible for it being one the acting user
   * may read (ADR-0008 §5). An empty scope short-circuits without any search or LLM call.
   */
  public List<Document> retrieveRelevantChunksInGivenScope(
      String question, List<Message> conversationHistory, Set<UUID> searchScope) {
    return retrieveRelevantChunksInGivenScope(
        question, conversationHistory, searchScope, MetadataFilter.NONE);
  }

  /** The same retrieval with a core-field filter carried into the run as given. */
  public List<Document> retrieveRelevantChunksInGivenScope(
      String question,
      List<Message> conversationHistory,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    return retrieveRelevantChunksInGivenScopeWithDecomposition(
            question, conversationHistory, searchScope, metadataFilter)
        .chunks();
  }

  /**
   * Chunks and the search queries the decomposition step (or its single-query fallback) actually
   * produced for them, one call each.
   *
   * @param chunks identical to what {@link #retrieveRelevantChunksInGivenScope} returns.
   * @param searchQueries the queries the search stages ran, in the order they were run — one entry
   *     when decomposition is disabled, off, or fails; 1 to {@link QueryProperties#maxSubQueries}
   *     entries when it succeeds. Empty only when {@code searchScope} was empty and no search ran
   *     at all.
   */
  public record RetrievalWithDecomposition(List<Document> chunks, List<String> searchQueries) {}

  /**
   * The same retrieval, additionally exposing the search queries the decomposition produced - what
   * the benchmark path needs to detect whether repeated runs of one question decomposed it
   * differently (docs/features/retrieval-benchmark.md §3). Same taken-as-given scope contract.
   *
   * <p>The explanation protocol is dropped here; a caller that evaluates it runs {@link
   * RetrievalPipeline#run} itself and keeps the whole {@link RetrievalPipelineResult}.
   */
  public RetrievalWithDecomposition retrieveRelevantChunksInGivenScopeWithDecomposition(
      String question, List<Message> conversationHistory, Set<UUID> searchScope) {
    return retrieveRelevantChunksInGivenScopeWithDecomposition(
        question, conversationHistory, searchScope, MetadataFilter.NONE);
  }

  /** The same retrieval with a core-field filter carried into the run as given. */
  public RetrievalWithDecomposition retrieveRelevantChunksInGivenScopeWithDecomposition(
      String question,
      List<Message> conversationHistory,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    RetrievalPipelineResult result =
        retrievalPipeline.run(
            new RetrievalContext(
                question,
                conversationHistory,
                searchScope,
                metadataFilter,
                queryProperties,
                // Read once per run, so every stage sees the same answer: fusion widens its
                // budget for the reranker only if the reranker can actually be called.
                RerankAvailability.of(rerankModelRole.currentStatus().state())));
    // Only for a run that actually searched: a "0 chunks across 0 search queries" line would
    // read like a failed retrieval rather than the deliberate empty-scope short-circuit.
    if (!result.searchQueries().isEmpty()) {
      log.debug(
          "Retrieved {} relevant chunks across {} search quer{} for query",
          result.chunks().size(),
          result.searchQueries().size(),
          result.searchQueries().size() == 1 ? "y" : "ies");
    }
    return new RetrievalWithDecomposition(result.chunks(), result.searchQueries());
  }

  /**
   * Groups by {@code document_id}, not {@code file_name}: two distinct documents that happen to
   * share a file name each get their own match count, the same collision {@link #mapSources} avoids
   * by keying its merge on {@code document_id} too.
   */
  private Map<String, Integer> countMatchesPerDocument(List<Document> chunks) {
    return chunks.stream()
        .collect(Collectors.groupingBy(ChunkGroupingKey::of, Collectors.summingInt(e -> 1)));
  }

  /**
   * Resolves each cited chunk's {@code document_id} to its persisted {@link
   * io.opaa.indexing.document.Document} - the single {@link DocumentRepository} lookup {@link
   * #mapSources} draws {@code indexedAt}, {@code sourceEntryUrl} and the source type from, rather
   * than one lookup per field. The values are read from the document instead of being duplicated
   * onto every chunk of the vector store.
   */
  private Map<String, io.opaa.indexing.document.Document> lookupSourceDocuments(
      List<Document> chunks) {
    Set<String> documentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());

    Map<String, io.opaa.indexing.document.Document> result = new LinkedHashMap<>();
    for (String docId : documentIds) {
      try {
        documentRepository
            .findById(UUID.fromString(docId))
            .ifPresent(doc -> result.put(docId, doc));
      } catch (IllegalArgumentException e) {
        // Not a transient failure: a chunk's document_id never fails to parse on its own, so this
        // signals a data problem, and WARN rather than DEBUG keeps it visible in production.
        log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", docId);
      }
    }
    return result;
  }

  /**
   * The core metadata fields (ADR-0024) of every document {@link #lookupSourceDocuments} resolved,
   * in one query - read from the document, never from the chunk, so every chunk of a document
   * reports the same title/Dokumentart/Datum and the origin travels along. A lookup failure is
   * logged and yields no core fields rather than failing the answer.
   */
  private Map<UUID, CoreMetadata> lookupCoreMetadata(
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId) {
    Set<UUID> ids =
        sourceDocumentsByDocId.values().stream()
            .map(io.opaa.indexing.document.Document::getId)
            .collect(Collectors.toSet());
    try {
      return documentMetadataService.coreMetadataFor(ids);
    } catch (RuntimeException e) {
      log.warn("Core metadata lookup failed for {} source document(s)", ids.size(), e);
      return Map.of();
    }
  }

  /**
   * Logs the number of invalid citations of one answer - one line per answer, and nothing at all
   * when every citation validated, so the log volume tracks only answers that need attention.
   */
  private void logInvalidCitations(List<CitationValidator.ValidatedCitation> validatedCitations) {
    long invalidCount = validatedCitations.stream().filter(c -> !c.valid()).count();
    if (invalidCount > 0) {
      log.info(
          "Answer contains {} invalid citation(s) out of {} total - flagged as invalid in the"
              + " response rather than silently dropped or silently kept as genuine",
          invalidCount,
          validatedCitations.size());
    }
  }

  /**
   * The library fields a Beleg shows for every resolved source document - at most two per library,
   * in their configured order. A lookup failure is logged and yields no library fields rather than
   * failing the answer, exactly like the core-field lookup.
   */
  private Map<UUID, List<CitationFieldValue>> lookupCitationFields(
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId) {
    try {
      return citationMetadataReader.forDocuments(sourceDocumentsByDocId.values());
    } catch (RuntimeException e) {
      log.warn(
          "Library citation metadata lookup failed for {} source document(s)",
          sourceDocumentsByDocId.size(),
          e);
      return Map.of();
    }
  }

  /**
   * Builds one {@link ChatSource} per retrieved document, deduplicated by {@code document_id} and
   * ranked gap-free by first appearance in the selection, plus a synthetic entry per invalid
   * citation whose document id matches no retrieved chunk. {@code cited} reflects valid citations
   * only, so a merely pattern-matching citation never counts as genuine.
   *
   * <p>A synthetic entry never goes through the merge of the real entries: a fabricated citation
   * can coincide in file name with a real document, and merging would let its {@code cited},
   * relevance score and document link overwrite the real values. It folds into a colliding real
   * entry by flipping that entry's {@code citationValid} to {@code false} and is appended as its
   * own row only when no real entry shares its file name. That collision check matches by file name
   * deliberately - a fabricated citation naming the right file but the wrong document id must still
   * flag every real entry of that name, there being no other signal for which one was meant.
   */
  private List<ChatSource> mapSources(
      List<Document> chunks,
      List<CitationValidator.ValidatedCitation> validatedCitations,
      Map<String, Integer> matchCounts,
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId,
      Map<UUID, CoreMetadata> coreMetadataByDocId,
      Map<UUID, List<CitationFieldValue>> citationFieldsByDocId,
      MetadataFilter metadataFilter) {
    Set<String> retrievedDocumentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .collect(Collectors.toSet());
    Set<String> validCitedDocumentIds =
        validatedCitations.stream()
            .filter(CitationValidator.ValidatedCitation::valid)
            .map(CitationValidator.ValidatedCitation::documentId)
            .collect(Collectors.toSet());
    Set<String> documentIdsWithInvalidCitation =
        validatedCitations.stream()
            .filter(c -> !c.valid())
            .map(CitationValidator.ValidatedCitation::documentId)
            .collect(Collectors.toSet());

    // Keyed on ChunkGroupingKey#of, not the parsed ChatSource#getDocumentId() (null for a
    // malformed/missing value) - two chunks with the same unparseable id must still merge into one
    // entry rather than colliding on a shared null key.
    Map<String, ChatSource> fromChunksByDocumentId =
        IntStream.range(0, chunks.size())
            .mapToObj(
                position -> {
                  Document chunk = chunks.get(position);
                  String fileName =
                      chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
                  String documentId =
                      chunk.getMetadata().getOrDefault("document_id", "").toString();
                  String groupKey = ChunkGroupingKey.of(chunk);
                  double score = relevanceScoreForRank(position + 1);
                  boolean cited = validCitedDocumentIds.contains(documentId);
                  boolean citationValid = !documentIdsWithInvalidCitation.contains(documentId);
                  int matches = matchCounts.getOrDefault(groupKey, 1);
                  io.opaa.indexing.document.Document sourceDocument =
                      sourceDocumentsByDocId.get(documentId);
                  Instant indexedAt = sourceDocument != null ? sourceDocument.getIndexedAt() : null;
                  String sourceEntryUrl =
                      sourceDocument != null ? sourceDocument.getSourceEntryUrl() : null;
                  CoreMetadata core =
                      sourceDocument != null
                          ? coreMetadataByDocId.getOrDefault(
                              sourceDocument.getId(), CoreMetadata.EMPTY)
                          : null;
                  List<ChatSourceMetadataEntry> metadataEntries =
                      core != null
                          ? ChatSourceMetadataEntry.from(
                              core,
                              citationFieldsByDocId.getOrDefault(sourceDocument.getId(), List.of()))
                          : List.of();
                  ChatSource reference =
                      new ChatSource(fileName, score, matches, cited)
                          .indexedAt(indexedAt)
                          .documentId(parseDocumentId(documentId))
                          .sourceType(
                              sourceDocument != null ? sourceDocument.getSourceType() : null)
                          .sourceUrl(
                              sourceDocument != null ? sourceDocument.getDeepLinkSourceUrl() : null)
                          .sourceEntryUrl(sourceEntryUrl)
                          .citationValid(citationValid)
                          .chunkLocations(chunkLocationOf(chunk))
                          .metadata(metadataEntries.isEmpty() ? null : metadataEntries)
                          .metadataFilterMatch(metadataFilterMatch(metadataFilter, core, chunk));
                  return Map.entry(groupKey, reference);
                })
            .collect(
                toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    QueryService::mergeSourceReferences,
                    LinkedHashMap::new));

    // The chunk-position score above is only the merge's tie-break for the "preferred" instance;
    // the value a client sees is the entry's own rank. The map's insertion order is the documents'
    // first-appearance order, so renumbering turns a chunk rank - which skips a position whenever
    // one document contributed two chunks - into a gap-free source rank.
    int sourceRank = 1;
    for (ChatSource source : fromChunksByDocumentId.values()) {
      source.setRelevanceScore(relevanceScoreForRank(sourceRank++));
    }

    List<ChatSource> orphanEntries =
        buildOrphanSourceReferences(validatedCitations, retrievedDocumentIds);
    List<ChatSource> unmatchedOrphanEntries = new ArrayList<>();
    for (ChatSource orphan : orphanEntries) {
      List<ChatSource> collidingRealEntries =
          fromChunksByDocumentId.values().stream()
              .filter(entry -> entry.getFileName().equals(orphan.getFileName()))
              .toList();
      if (!collidingRealEntries.isEmpty()) {
        collidingRealEntries.forEach(entry -> entry.setCitationValid(false));
      } else {
        unmatchedOrphanEntries.add(orphan);
      }
    }

    return Stream.concat(fromChunksByDocumentId.values().stream(), unmatchedOrphanEntries.stream())
        .toList();
  }

  /**
   * Whether a retrieved document matched every filtered field or was kept by the Leerwert rule
   * alone. Read from the chunk's own metadata keys - the ones both search paths filtered on, so the
   * mark cannot disagree with the condition that let the chunk through, and a library field is
   * covered without a second query per document. Null without an active filter, and for a chunk
   * whose document no longer resolves.
   */
  static MetadataFilterMatch metadataFilterMatch(
      MetadataFilter filter, CoreMetadata core, Document chunk) {
    if (filter == null || filter.isEmpty() || core == null) {
      return null;
    }
    return MetadataFilterExpressions.keptWithoutValue(filter, chunk)
        ? MetadataFilterMatch.NO_VALUE
        : MetadataFilterMatch.MATCHED;
  }

  /**
   * The reciprocal of a 1-based position - {@code 1.0} for the first, strictly decreasing and
   * always within {@code (0, 1]}, so it stays inside {@code SourceReference#relevanceScore}'s
   * declared bounds. A position is comparable across search paths, a raw {@link
   * Document#getScore()} is not.
   */
  private static double relevanceScoreForRank(int rank) {
    return 1.0 / rank;
  }

  /**
   * Parses a chunk's {@code document_id} metadata value into a {@link UUID} for {@link
   * ChatSource#getDocumentId()}, returning {@code null} for an empty or malformed value rather than
   * throwing: a chunk with corrupt metadata must not fail the whole answer.
   */
  private static UUID parseDocumentId(String documentId) {
    if (documentId.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(documentId);
    } catch (IllegalArgumentException e) {
      // Same rationale as lookupSourceDocuments above: a data problem, not a transient error.
      log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", documentId);
      return null;
    }
  }

  /**
   * Builds one synthetic {@link ChatSource} per distinct file name an invalid citation claimed for
   * a document id no retrieved chunk carries - the only way such a citation can be flagged, since
   * no real entry would carry the flag. {@code relevanceScore} and {@code matchCount} are {@code
   * 0}: there is no retrieved passage behind the entry, not merely a weak one. {@code cited = true}
   * is deliberate - the citation is why the entry exists, so it must not be sorted into "checked
   * but uncited", which would present a fabricated reference as a retrieved but unused document.
   */
  private List<ChatSource> buildOrphanSourceReferences(
      List<CitationValidator.ValidatedCitation> validatedCitations,
      Set<String> retrievedDocumentIds) {
    Set<String> orphanFileNames =
        validatedCitations.stream()
            .filter(c -> !c.valid())
            .filter(c -> !retrievedDocumentIds.contains(c.documentId()))
            .map(CitationValidator.ValidatedCitation::fileName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return orphanFileNames.stream()
        .map(fileName -> new ChatSource(fileName, 0.0, 0, true).citationValid(false))
        .toList();
  }

  /**
   * Merges duplicate source references of the same <b>document</b> - the dedupe key is {@code
   * document_id}, never {@code fileName} - keeping the higher-scoring one and marking the result
   * cited if either side was: a cited chunk means the document contributed to the answer.
   *
   * <p>{@code a} and {@code b} therefore always share one underlying document row, so {@code
   * documentId}, {@code sourceType} and {@code sourceUrl} are equal between them.
   */
  static ChatSource mergeSourceReferences(ChatSource a, ChatSource b) {
    ChatSource preferred = a.getRelevanceScore() >= b.getRelevanceScore() ? a : b;
    boolean shouldBeCited = a.getCited() || b.getCited();
    // Valid only if neither side carries an invalid citation: "valid" must hold for every
    // citation of this document, so one invalid citation flags the merged entry.
    boolean mergedCitationValid = isCitationValid(a) && isCitationValid(b);
    String mergedSourceEntryUrl =
        Objects.equals(a.getSourceEntryUrl(), b.getSourceEntryUrl())
            ? preferred.getSourceEntryUrl()
            : null;
    // Every retrieved chunk keeps its own location entry, ordered by chunk index, so any
    // footnote of this document resolves - not only the best-scoring chunk's.
    List<ChatSourceLocation> mergedChunkLocations = mergeChunkLocations(a, b);
    // ADR-0024: schema metadata hangs on the document, so both sides carry the same list or none.
    List<ChatSourceMetadataEntry> mergedMetadata =
        preferred.getMetadata() != null
            ? preferred.getMetadata()
            : a.getMetadata() != null ? a.getMetadata() : b.getMetadata();

    if (shouldBeCited && !preferred.getCited()) {
      return new ChatSource(
              preferred.getFileName(),
              preferred.getRelevanceScore(),
              preferred.getMatchCount(),
              true)
          .indexedAt(preferred.getIndexedAt())
          .documentId(preferred.getDocumentId())
          .sourceType(preferred.getSourceType())
          .sourceUrl(preferred.getSourceUrl())
          .sourceEntryUrl(mergedSourceEntryUrl)
          .citationValid(mergedCitationValid)
          .chunkLocations(mergedChunkLocations)
          .metadata(mergedMetadata);
    }

    preferred.setSourceEntryUrl(mergedSourceEntryUrl);
    preferred.setCitationValid(mergedCitationValid);
    preferred.setChunkLocations(mergedChunkLocations);
    preferred.setMetadata(mergedMetadata);
    return preferred;
  }

  private static List<ChatSourceLocation> mergeChunkLocations(ChatSource a, ChatSource b) {
    Map<Integer, ChatSourceLocation> byIndex = new TreeMap<>();
    Stream.of(a.getChunkLocations(), b.getChunkLocations())
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .forEach(location -> byIndex.putIfAbsent(location.getChunkIndex(), location));
    return new ArrayList<>(byIndex.values());
  }

  /**
   * The location entry of one retrieved chunk: its {@code chunk_index} - the number the citation
   * marker names - and the {@code location} the indexing pipeline stored, null when it stored none.
   * A chunk without a usable {@code chunk_index} yields no entry: there is no number a footnote
   * could be resolved by.
   */
  private static List<ChatSourceLocation> chunkLocationOf(Document chunk) {
    Object rawIndex = chunk.getMetadata().get("chunk_index");
    if (rawIndex == null) {
      return new ArrayList<>();
    }
    int chunkIndex;
    try {
      chunkIndex = Integer.parseInt(rawIndex.toString().trim());
    } catch (NumberFormatException e) {
      return new ArrayList<>();
    }
    Object location = chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY);
    List<ChatSourceLocation> result = new ArrayList<>(1);
    result.add(
        new ChatSourceLocation(chunkIndex).location(location != null ? location.toString() : null));
    return result;
  }

  /**
   * The libraries the search actually ran against, by name - the "Durchsucht wurden: …" line under
   * an unsubstantiated answer. Resolved from the effective {@code searchScope}, never from the
   * request, so it reflects permissions and the chat's settings exactly as the search did.
   */
  private List<SearchedLibraryRef> searchedLibraries(Set<UUID> searchScope) {
    if (searchScope.isEmpty()) {
      return new ArrayList<>();
    }
    return knowledgeLibraryRepository.findAllById(searchScope).stream()
        .map(library -> new SearchedLibraryRef(library.getId(), library.getName()))
        .sorted(Comparator.comparing(SearchedLibraryRef::getName, String.CASE_INSENSITIVE_ORDER))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  /** {@code citationValid} defaults to {@code true}: absent means never flagged invalid. */
  private static boolean isCitationValid(ChatSource source) {
    Boolean citationValid = source.getCitationValid();
    return citationValid == null || citationValid;
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
}
