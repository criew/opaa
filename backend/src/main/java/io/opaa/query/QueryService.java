package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.auth.CurrentUser;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
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
import java.util.stream.Stream;
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
import org.springframework.stereotype.Service;

/** {@code @Service} (#889, O2): previously wired manually in {@code QueryConfiguration}. */
@Service
public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private final VectorStore vectorStore;
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

  public QueryService(
      VectorStore vectorStore,
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
      KnowledgeLibraryRepository knowledgeLibraryRepository) {
    this.vectorStore = vectorStore;
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
  }

  /**
   * Answers {@code question}, restricted to chunks from libraries {@code currentUserId} may read
   * (#202). The filter is part of the {@link VectorStore#similaritySearch} call itself, not a
   * post-filter - an unauthorized chunk is never loaded or ranked. No system-admin bypass here
   * (unlike {@code LibraryAccessService#effectiveRole}): a query always reads with the calling
   * user's own rights, with no second rights context (ADR-0008 §5).
   *
   * <p>An empty readable set short-circuits before the vector store is even called, skipping
   * straight to answer generation with zero chunks - the same path a genuinely empty result takes,
   * so the message cannot distinguish "no permission on anything" from "nothing matched" (#202
   * acceptance criteria).
   *
   * <p><b>Persisted chats (#525).</b> {@code chatId} is optional. When it names a chat {@code
   * currentUserId} authored (see {@link ChatService#findOwnedChat}), the query runs against that
   * chat: the search scope comes from the chat's own {@code useKnowledge}/{@code
   * referencedLibraryIds} ({@link ChatService#effectiveLibraryScope}) - the parameters below are
   * then ignored, not merely defaulted - question and answer are persisted as {@link
   * io.opaa.chat.ChatMessage}s, and the conversation-memory cache ({@link #chatMemory}) is seeded
   * from the persisted history on a cache miss. When {@code chatId} is absent or does not resolve
   * to an owned chat, the query runs ephemerally instead: not persisted, scope governed by {@code
   * useKnowledge}/{@code requestedLibraryIds} (#526), keyed in the in-memory cache by the
   * caller-supplied {@code chatId} when given, or a freshly generated one.
   *
   * <p><b>Permission-history regression check</b> (#238, sampled per {@link
   * #maybeCheckAgainstPermissionHistory} at {@link QueryProperties#permissionHistorySampleRate}):
   * the live readable set is compared against {@link
   * PermissionHistoryService#readableLibraryIdsAsOf}'s reconstruction for the same instant, logging
   * a warning if the live computation reaches a library the history would not - a beweisbarer
   * Durchsetzungsfehler per
   * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten. Only a
   * detected mismatch - not every sampled query - is logged, and only the offending library id,
   * never the caller's whole readable set (personal-data minimization per the same section).
   *
   * <p><b>Search-scope controls</b> {@code useKnowledge}/{@code requestedLibraryIds} (#526,
   * consulted only without a persisted chat): {@code useKnowledge = true} uses every library {@code
   * currentUserId} may read; {@code useKnowledge = false} narrows to {@code requestedLibraryIds}
   * intersected with the readable set - never widened beyond it, so a referenced but unreadable
   * library yields no hits rather than being silently granted. An empty intersection also takes the
   * empty-scope short-circuit above and marks {@link QueryOutcome#getAnsweredWithoutKnowledge()}.
   *
   * <p><b>Deliberately <em>not</em> {@code @Transactional}</b> (same reasoning as {@code
   * UserService#findOrCreateUser}, same class of bug #299 fixed there): an ambient transaction here
   * would hold one JDBC connection open for the entire call, including the LLM call inside {@code
   * answerGenerationService.generateAnswer}, while {@code ChatService#appendTurn} afterwards needs
   * a second, independently held connection to write - under concurrent persisted-chat traffic this
   * exhausts the pool (a full deadlock, not merely contention) once every caller's connection is
   * claimed and waiting on the LLM response. Without an ambient transaction here, every
   * repository/service call below is instead independently transactional and releases its
   * connection immediately, exactly like {@code UserService.findOrCreateUser}.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds) {
    UUID currentUserId = caller.id();
    return metrics
        .queryTimer()
        .record(
            () -> {
              try {
                // --- Read phase (#889): membership/archive/scope checks and the vector search
                // below all run without any ambient transaction of their own - each repository
                // call opens and releases its own short-lived connection (see this method's
                // Javadoc's "Deliberately not @Transactional" section).
                Optional<Chat> chat = chatService.findOwnedChat(chatId, currentUserId);
                // Querying is chatting: requires space membership even for an author who already
                // owns the chat - see ChatService#requireStillSpaceMember's Javadoc for why this
                // check lives only on this path and not on getChat/updateChat/deleteChat.
                chat.ifPresent(chatService::requireStillSpaceMember);
                // An archived space accepts no new content - checked here, before retrieval/the
                // LLM call, so the ordinary case never pays for an LLM call whose answer appendTurn
                // below would discard anyway. appendTurn's own call to the same guard stays in
                // place as the race guard for a space archived after this point.
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

                String searchQuery = buildSearchQuery(question, conversationKey);

                long startTime = System.currentTimeMillis();

                Instant scopeComputedAt = Instant.now();
                Set<UUID> readableLibraryIds =
                    libraryAccessService.readableLibraryIds(currentUserId, caller.organizationId());
                maybeCheckAgainstPermissionHistory(
                    readableLibraryIds, currentUserId, caller.organizationId(), scopeComputedAt);

                // A persisted chat's own settings govern the scope entirely; only an ephemeral
                // query (no owned chat) falls back to the request-level useKnowledge/
                // requestedLibraryIds (#526).
                Set<UUID> searchScope =
                    chat.map(c -> chatService.effectiveLibraryScope(c, readableLibraryIds))
                        .orElseGet(
                            () ->
                                useKnowledge
                                    ? readableLibraryIds
                                    : intersectWithReadable(
                                        requestedLibraryIds, readableLibraryIds));
                boolean effectiveUseKnowledge = chat.map(Chat::isUseKnowledge).orElse(useKnowledge);
                boolean answeredWithoutKnowledge = !effectiveUseKnowledge && searchScope.isEmpty();
                // Distinct from answeredWithoutKnowledge above: the #203 fail-open case where the
                // chip stays on @Alles-Wissen but the chat's space is curated and none of its
                // associated libraries are readable by this caller, so effectiveLibraryScope
                // legitimately resolves to empty. Only meaningful for a persisted chat - an
                // ephemeral query's empty searchScope instead means the caller simply has no
                // readable library at all. See ChatService#spaceHasLibraryAssociations's Javadoc.
                boolean noKnowledgeAvailableInSpace =
                    effectiveUseKnowledge
                        && searchScope.isEmpty()
                        && chat.map(c -> chatService.spaceHasLibraryAssociations(c.getSpaceId()))
                            .orElse(false);

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

                // --- LLM call: the slowest step, and the reason no phase in this method carries a
                // transaction - see this method's Javadoc's "Deliberately not @Transactional".
                ChatResponse chatResponse =
                    answerGenerationService.generateAnswer(
                        question, relevantChunks, conversationKey);

                String answer = extractAnswer(chatResponse);
                List<CitationValidator.ValidatedCitation> validatedCitations =
                    citationValidator.validate(
                        citationParser.extractCitations(answer), relevantChunks);
                logInvalidCitations(validatedCitations);
                Map<String, Integer> matchCounts = countMatchesPerDocument(relevantChunks);
                Map<String, io.opaa.indexing.Document> sourceDocumentsByDocId =
                    lookupSourceDocuments(relevantChunks);
                List<ChatSource> sources =
                    mapSources(
                        relevantChunks, validatedCitations, matchCounts, sourceDocumentsByDocId);

                log.debug(
                    "Citations found: {} validated, {} total sources",
                    validatedCitations.size(),
                    sources.size());

                long durationMs = System.currentTimeMillis() - startTime;
                String model = extractModel(chatResponse);
                int tokenCount = extractTokenCount(chatResponse);

                metrics.recordSuccess(tokenCount);

                // --- Write phase: the one place this method's result is persisted, in
                // ChatService#appendTurn's own transaction(s) - see that method's Javadoc.
                // appendTurn's title/title_source writes go through atomic, targeted
                // ChatRepository updates rather than mutating the `chat` instance loaded above, so
                // its return value (not `chat`) is this method's source of truth for the title -
                // the fallback title on a first turn, never the LLM-derived one, which generates
                // asynchronously after this response is built (see ChatTitleGenerationService).
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
   * {@code requestedLibraryIds ∩ readableLibraryIds} - the #526 search scope for an ephemeral query
   * (no persisted chat) with {@code useKnowledge = false}. Deliberately never adds anything beyond
   * {@code readableLibraryIds}: a reference to a library the caller cannot read is silently
   * dropped, not honoured. A persisted chat's sticky references go through {@link
   * ChatService#effectiveLibraryScope} instead, which applies the identical rule.
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
   * mechanism that makes {@link #buildSearchQuery} and {@link
   * AnswerGenerationService#generateAnswer} see the persisted history even though neither was
   * changed to read from the database directly (#525's "Gesprächsgedächtnis speist sich aus den
   * persistierten Nachrichten (Caffeine darf Cache bleiben)"). Only touches the cache when it is
   * actually empty for this key - a chat with history already in the warm cache is left alone, both
   * because re-adding would duplicate every message and because the warm cache is already
   * authoritative for the current process.
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
   * QueryProperties#permissionHistorySampleRate} of queries instead of running it on every one -
   * see that field's Javadoc for why the reconstruction cost is unnecessary on every request for a
   * drift signal that either never fires or keeps firing on every query until fixed. {@code
   * sampleRate = 1.0} runs the check every time; {@code sampleRate = 0.0} never runs it. The dice
   * roll happens here, not inside {@link #checkAgainstPermissionHistory} itself, which stays a
   * plain, deterministic, directly testable check.
   */
  private void maybeCheckAgainstPermissionHistory(
      Set<UUID> readableScope, UUID currentUserId, UUID organizationId, Instant asOf) {
    if (ThreadLocalRandom.current().nextDouble() >= queryProperties.permissionHistorySampleRate()) {
      return;
    }
    checkAgainstPermissionHistory(readableScope, currentUserId, organizationId, asOf);
  }

  /**
   * #238's regression check - see {@link #query}'s Javadoc. {@code readableScope} is the full set
   * {@link LibraryAccessService#readableLibraryIds} computed for this query at {@code asOf} - not
   * necessarily the narrower {@code searchScope} #525/#526 may actually hand to the vector store,
   * since either can restrict the search to a subset of what is merely readable. Any id in {@code
   * readableScope} the permission history does not also grant as of {@code asOf} is a mismatch,
   * logged as a single warning per query (not once per offending library - code review of #427, nit
   * 2), never silently ignored. {@code asOf} is the instant {@code readableScope} was itself
   * computed at, not a fresh {@code Instant.now()} taken here - reusing it avoids a false-positive
   * mismatch from a permission change landing in the gap between the two computations. Not sampled
   * itself - see {@link #maybeCheckAgainstPermissionHistory}, its only caller, for the sampling
   * decision (#889, O1).
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
   * Builds the {@code library_id IN (...)} filter passed straight into {@link
   * VectorStore#similaritySearch} - see {@link #query} for why this must be part of the search
   * call, never a filter applied to its result afterwards.
   */
  private Filter.Expression libraryFilter(Set<UUID> readableLibraryIds) {
    List<Object> libraryIdValues =
        readableLibraryIds.stream().map(UUID::toString).map(Object.class::cast).toList();
    return new FilterExpressionBuilder()
        .in(VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryIdValues)
        .build();
  }

  /**
   * Groups by {@code document_id}, not {@code file_name} (#739): two distinct documents that happen
   * to share a file name must each get their own match count, the same collision {@link
   * #mapSources} now avoids by keying its merge on {@code document_id} too.
   */
  private Map<String, Integer> countMatchesPerDocument(List<Document> chunks) {
    return chunks.stream()
        .collect(
            Collectors.groupingBy(QueryService::chunkGroupingKey, Collectors.summingInt(e -> 1)));
  }

  /**
   * Groups a chunk by its {@code document_id} metadata, falling back to {@code file_name} when that
   * metadata is missing or empty - a chunk without {@code document_id} can only occur for
   * pre-#739 index entries, since {@code FileProcessingService#storeChunks} now writes it on every
   * chunk. Using the same {@code file_name} fallback consistently across {@link
   * #countMatchesPerDocument} and {@link #mapSources} keeps two such chunks from <em>different</em>
   * documents from collapsing into one merged entry via a shared empty-string key.
   */
  private static String chunkGroupingKey(Document chunk) {
    String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
    if (!documentId.isEmpty()) {
      return documentId;
    }
    return "file:" + chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
  }

  /**
   * Resolves each cited chunk's {@code document_id} to its persisted {@link
   * io.opaa.indexing.Document} - the single {@link DocumentRepository} lookup {@link #mapSources}
   * draws both {@code indexedAt} and {@code sourceEntryUrl} from (#639), rather than a second,
   * duplicate lookup per field. {@code sourceEntryUrl} follows the same document_id-lookup pattern
   * this method already used for {@code indexedAt} alone - see the comment in {@code
   * FileProcessingService#storeChunks} for why the value is not instead duplicated onto every chunk
   * in the vector store.
   */
  private Map<String, io.opaa.indexing.Document> lookupSourceDocuments(List<Document> chunks) {
    Set<String> documentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());

    Map<String, io.opaa.indexing.Document> result = new LinkedHashMap<>();
    for (String docId : documentIds) {
      try {
        documentRepository
            .findById(UUID.fromString(docId))
            .ifPresent(doc -> result.put(docId, doc));
      } catch (IllegalArgumentException e) {
        // #78: not a transient failure - a chunk's document_id metadata never fails to parse on
        // its own, so this signals a data problem (corrupt indexing, a botched migration, or a
        // version mismatch between indexer and query service) that DEBUG would hide in
        // production.
        log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", docId);
      }
    }
    return result;
  }

  /**
   * Logs the number of invalid citations found in this answer's response (#386) - deliberately a
   * single log line per answer, not a new metric: the issue's scope is the deterministic validation
   * itself, not new metrics infrastructure. Nothing is logged when every citation validated, so the
   * log volume tracks only answers that actually need attention.
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
   * Builds one {@link ChatSource} per retrieved file, plus a synthetic entry for every invalid
   * citation whose document id matches none of the retrieved chunks at all (#386) - the only case
   * where an invalid citation cannot attach to a real retrieved chunk's source entry, since it
   * points at a document this answer never actually searched. {@code cited} only reflects
   * <em>valid</em> citations - an invalid one never makes an unrelated, merely pattern-matching
   * citation count as genuine.
   *
   * <p>Synthetic entries deliberately do <b>not</b> go through the same file-name merge as the
   * real, retrieved-chunk entries: a fabricated citation can coincide in file name with a real,
   * retrieved document, and merging would let the fabricated citation's {@code cited = true},
   * relevance score and document link overwrite the real entry's own values. A colliding synthetic
   * entry instead folds into the matching real entry by flipping only its {@code citationValid} to
   * {@code false} - {@code cited}, relevance score, match count and document link stay exactly as
   * the real, retrieved chunk(s) determined them. A synthetic entry is appended as its own row only
   * when no real entry shares its file name (avoiding two rows sharing one {@code fileName}, which
   * {@code frontend/src/components/chat/citations.ts} would resolve last-wins).
   *
   * <p>Real entries are deduped by {@code document_id}, not {@code fileName} (#739): two distinct
   * documents sharing a file name each keep their own {@link ChatSource} row. The orphan-collision
   * check below still matches by {@code fileName} deliberately - a fabricated citation naming the
   * right file name but the wrong document id must still flag every real entry sharing that file
   * name, since there is no other signal for which one the model meant.
   */
  private List<ChatSource> mapSources(
      List<Document> chunks,
      List<CitationValidator.ValidatedCitation> validatedCitations,
      Map<String, Integer> matchCounts,
      Map<String, io.opaa.indexing.Document> sourceDocumentsByDocId) {
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

    // Keyed on #chunkGroupingKey, not the parsed ChatSource#getDocumentId() (null for a
    // malformed/missing value) - two chunks with the same unparseable id must still merge into one
    // entry rather than colliding on a shared null key.
    Map<String, ChatSource> fromChunksByDocumentId =
        chunks.stream()
            .map(
                chunk -> {
                  String fileName =
                      chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
                  String documentId =
                      chunk.getMetadata().getOrDefault("document_id", "").toString();
                  String groupKey = chunkGroupingKey(chunk);
                  double score = chunk.getScore() != null ? chunk.getScore() : 0.0;
                  boolean cited = validCitedDocumentIds.contains(documentId);
                  boolean citationValid = !documentIdsWithInvalidCitation.contains(documentId);
                  int matches = matchCounts.getOrDefault(groupKey, 1);
                  io.opaa.indexing.Document sourceDocument = sourceDocumentsByDocId.get(documentId);
                  Instant indexedAt = sourceDocument != null ? sourceDocument.getIndexedAt() : null;
                  String sourceEntryUrl =
                      sourceDocument != null ? sourceDocument.getSourceEntryUrl() : null;
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
                          .chunkLocations(chunkLocationOf(chunk));
                  return Map.entry(groupKey, reference);
                })
            .collect(
                toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    QueryService::mergeSourceReferences,
                    LinkedHashMap::new));

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
   * Parses a chunk's {@code document_id} metadata value into a {@link UUID} for {@link
   * ChatSource#getDocumentId()} (#739), returning {@code null} for an empty or malformed value
   * rather than throwing - the same defensive handling {@link #lookupSourceDocuments} already
   * applies to the identical metadata field, since a chunk with missing/corrupt metadata must not
   * fail the whole answer.
   */
  private static UUID parseDocumentId(String documentId) {
    if (documentId.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(documentId);
    } catch (IllegalArgumentException e) {
      // #78: same rationale as lookupSourceDocuments above - WARN, not DEBUG, since this
      // indicates a data problem rather than a transient error.
      log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", documentId);
      return null;
    }
  }

  /**
   * Builds one synthetic {@link ChatSource} per distinct file name an invalid citation claimed for
   * a document id that matches no retrieved chunk at all (#386) - this is the only way such a
   * citation can be flagged at all, since it does not correspond to any real retrieved chunk that
   * would otherwise carry the flag. {@code relevanceScore} and {@code matchCount} are both {@code
   * 0} - the honest signal that there is no real retrieved passage behind this entry, not merely a
   * low one.
   *
   * <p>#697 review, finding 4: {@code cited = true} is deliberate, not an oversight - the citation
   * is why this entry exists at all, so it must not be sorted into "checked but uncited" ({@link
   * #mapSources}'s uncited group), which would misrepresent a fabricated reference as a real
   * document that was merely retrieved and not used.
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
   * Merges duplicate source references for the same <b>document</b> (dedupe key is {@code
   * document_id}, not {@code fileName} - #739), keeping the one with the highest relevance score
   * while preserving citation status. If either reference was cited in the answer, the merged
   * result is marked as cited - any chunk from that document being cited means the document as a
   * whole contributed to the answer.
   *
   * <p>{@code a} and {@code b} always share the same {@code document_id} and therefore the same
   * underlying {@link io.opaa.indexing.Document} row - {@code documentId}, {@code sourceType},
   * {@code sourceUrl} and {@code sourceEntryUrl} are consequently always equal between them, unlike
   * under a fileName key where two genuinely different documents could disagree.
   */
  static ChatSource mergeSourceReferences(ChatSource a, ChatSource b) {
    ChatSource preferred = a.getRelevanceScore() >= b.getRelevanceScore() ? a : b;
    boolean shouldBeCited = a.getCited() || b.getCited();
    // #386: valid only if neither side carries an invalid citation - one invalid citation for
    // this file is enough to flag the merged entry, mirroring shouldBeCited's OR but inverted,
    // since "valid" is the property that must hold for *every* citation, not just one.
    boolean mergedCitationValid = isCitationValid(a) && isCitationValid(b);
    String mergedSourceEntryUrl =
        Objects.equals(a.getSourceEntryUrl(), b.getSourceEntryUrl())
            ? preferred.getSourceEntryUrl()
            : null;
    // #667: every retrieved chunk keeps its own location entry, ordered by chunk index, so the
    // frontend can resolve any footnote of this document - not only the best-scoring chunk's.
    List<ChatSourceLocation> mergedChunkLocations = mergeChunkLocations(a, b);

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
          .chunkLocations(mergedChunkLocations);
    }

    preferred.setSourceEntryUrl(mergedSourceEntryUrl);
    preferred.setCitationValid(mergedCitationValid);
    preferred.setChunkLocations(mergedChunkLocations);
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
   * The #667 location entry of one retrieved chunk: its {@code chunk_index} (the number the
   * citation marker names) and the {@code location} the indexing pipeline stored, null when it
   * stored none. A chunk without a usable {@code chunk_index} (legacy data predating the metadata)
   * yields no entry at all - there is no number a footnote could be resolved by.
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
   * The libraries the vector search actually ran against (#667), by name - what mockup 1a's
   * "Durchsucht wurden: …" line under an unsubstantiated answer names. Resolved from the effective
   * {@code searchScope}, never from the request, so it reflects permissions and the chat's own
   * settings exactly as the search did. Empty when no search ran.
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

  /** {@code citationValid} defaults to {@code true} (absent = never flagged invalid) - #386. */
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
