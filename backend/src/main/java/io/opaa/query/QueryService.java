package io.opaa.query;

import io.opaa.auth.CurrentUser;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataFilterValidator;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.answer.ChatResponses;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import io.opaa.query.retrieval.search.SubQueryDecompositionStage;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * {@link RetrievalPipeline} over it, has the answer generated, validates the citations and has the
 * source rows built.
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
  private final RetrievalContextFactory retrievalContextFactory;
  private final SearchScopeResolver searchScopeResolver;
  private final ChatSourceAssembler chatSourceAssembler;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final LibraryAccessService libraryAccessService;
  private final PermissionHistoryService permissionHistoryService;
  private final ChatService chatService;
  private final QueryMetrics metrics;
  private final QueryProperties queryProperties;
  private final MetadataFilterValidator metadataFilterValidator;

  public QueryService(
      RetrievalPipeline retrievalPipeline,
      RetrievalContextFactory retrievalContextFactory,
      SearchScopeResolver searchScopeResolver,
      ChatSourceAssembler chatSourceAssembler,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      CitationValidator citationValidator,
      LibraryAccessService libraryAccessService,
      PermissionHistoryService permissionHistoryService,
      ChatService chatService,
      QueryMetrics metrics,
      QueryProperties queryProperties,
      MetadataFilterValidator metadataFilterValidator) {
    this.retrievalPipeline = retrievalPipeline;
    this.retrievalContextFactory = retrievalContextFactory;
    this.searchScopeResolver = searchScopeResolver;
    this.chatSourceAssembler = chatSourceAssembler;
    this.answerGenerationService = answerGenerationService;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.libraryAccessService = libraryAccessService;
    this.permissionHistoryService = permissionHistoryService;
    this.chatService = chatService;
    this.metrics = metrics;
    this.queryProperties = queryProperties;
    this.metadataFilterValidator = metadataFilterValidator;
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
                    searchScopeResolver.resolveSearchScope(
                        chat, useKnowledge, requestedLibraryIds, readableLibraryIds);
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
                      retrieve(
                          question, chatMemory.get(conversationKey), searchScope, metadataFilter);
                }

                // --- LLM call: the slowest step, and the reason no phase of this method
                // carries a transaction.
                ChatResponse chatResponse =
                    answerGenerationService.generateAnswer(
                        question, relevantChunks, conversationKey);

                String answer = ChatResponses.text(chatResponse);
                List<CitationValidator.ValidatedCitation> validatedCitations =
                    citationValidator.validate(
                        citationParser.extractCitations(answer), relevantChunks, answer);
                List<ChatSource> sources =
                    chatSourceAssembler.assemble(
                        relevantChunks, validatedCitations, metadataFilter);

                log.debug(
                    "Citations found: {} validated, {} total sources",
                    validatedCitations.size(),
                    sources.size());

                long durationMs = System.currentTimeMillis() - startTime;
                String model = ChatResponses.model(chatResponse);
                int tokenCount = ChatResponses.totalTokens(chatResponse);

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
                    new QueryOutcome(
                        model,
                        tokenCount,
                        durationMs,
                        answeredWithoutKnowledge,
                        noKnowledgeAvailableInSpace,
                        chatSourceAssembler.searchedLibraries(searchScope));
                return new QueryResult(answer, sources, metadata, effectiveChatId, chatTitle);
              } catch (RuntimeException e) {
                metrics.recordError();
                throw e;
              }
            });
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
   * The retrieval half of {@link #query}: runs the whole {@link RetrievalPipeline} over the given
   * scope and returns the chunks in the order and count the answer prompt is built from. The
   * explanation protocol is dropped here; the administration's diagnosis and the evaluation harness
   * run the pipeline themselves and keep the whole {@link RetrievalPipelineResult}.
   */
  private List<Document> retrieve(
      String question,
      List<Message> conversationHistory,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    RetrievalPipelineResult result =
        retrievalPipeline.run(
            retrievalContextFactory.contextFor(
                question, conversationHistory, searchScope, metadataFilter));
    // Only for a run that actually searched: a "0 chunks across 0 search queries" line would
    // read like a failed retrieval rather than the deliberate empty-scope short-circuit.
    if (!result.searchQueries().isEmpty()) {
      log.debug(
          "Retrieved {} relevant chunks across {} search quer{} for query",
          result.chunks().size(),
          result.searchQueries().size(),
          result.searchQueries().size() == 1 ? "y" : "ies");
    }
    return result.chunks();
  }
}
