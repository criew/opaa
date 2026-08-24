package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.common.UnauthorizedException;
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

public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private final VectorStore vectorStore;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final DocumentRepository documentRepository;
  private final UserRepository userRepository;
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
      UserRepository userRepository,
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
    this.userRepository = userRepository;
    this.libraryAccessService = libraryAccessService;
    this.permissionHistoryService = permissionHistoryService;
    this.chatService = chatService;
    this.metrics = metrics;
    this.queryProperties = queryProperties;
    this.knowledgeLibraryRepository = knowledgeLibraryRepository;
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
   * <p><b>#525 - persisted chats.</b> {@code chatId} is optional. When it names a chat {@code
   * currentUserId} authored (see {@link ChatService#findOwnedChat}), the query runs against that
   * chat: the search scope comes from the chat's own {@code useKnowledge}/{@code
   * referencedLibraryIds} settings ({@link ChatService#effectiveLibraryScope}) - the {@code
   * useKnowledge}/{@code requestedLibraryIds} parameters below are then ignored, not merely
   * defaulted - the question and answer are persisted as {@link io.opaa.chat.ChatMessage}s, and the
   * conversation-memory cache ({@link #chatMemory}, still Caffeine-backed - see {@code
   * CaffeineChatMemoryRepository}) is seeded from the persisted history on a cache miss, so a
   * restart or eviction never loses context for a persisted chat. When {@code chatId} is absent, or
   * does not resolve to a chat the caller authored, the query runs ephemerally instead: not
   * persisted, and the search scope is governed by {@code useKnowledge}/{@code requestedLibraryIds}
   * exactly as #526 introduced them, remembered only in the in-memory cache under a key reused from
   * a caller-supplied {@code chatId} when one was given, or freshly generated otherwise.
   *
   * <p><b>#238's regression check:</b> the readable set ({@code readableLibraryIds} below, distinct
   * from the narrower {@code searchScope} #525/#526 may derive from it) is compared against {@link
   * PermissionHistoryService#readableLibraryIdsAsOf}'s reconstruction for the same instant, logging
   * a warning if the live computation reaches a library the history would not - a beweisbarer
   * Durchsetzungsfehler per
   * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten.
   * Deliberately not a per-query log line of the full permission set itself: the feature spec
   * rejects that as an unnecessary expansion of personal data (see the same section), so only a
   * detected mismatch - not every query - is written to the application log, and even then only the
   * offending library id, not the caller's whole readable set.
   *
   * <p><b>#526's search-scope controls</b>, {@code useKnowledge} and {@code requestedLibraryIds}
   * (only consulted for a query with no persisted chat, see above): {@code useKnowledge = true}
   * preserves the behaviour above exactly - every library {@code currentUserId} may read, {@code
   * requestedLibraryIds} ignored. {@code useKnowledge = false} narrows the scope to {@code
   * requestedLibraryIds} intersected with the readable set - never widened beyond it, matching
   * #526's acceptance criteria that a referenced but unreadable library yields no hits rather than
   * being silently granted. An empty intersection in that mode also takes the empty-scope
   * short-circuit above and additionally marks {@link QueryOutcome#getAnsweredWithoutKnowledge()}
   * so the caller can distinguish "no knowledge base searched" from "searched but found nothing" -
   * the same distinction applies to a persisted chat whose own {@code useKnowledge} is off with no
   * (readable) sticky reference.
   *
   * <p><b>Deliberately <em>not</em> {@code @Transactional}</b> (#525 review round 2, finding A -
   * the same reasoning {@code UserService#findOrCreateUser} documents, and the same class of bug
   * #299 fixed there): this method used to carry {@code @Transactional(readOnly = true)}, which
   * held one JDBC connection open for its entire duration - including the LLM call inside {@code
   * answerGenerationService.generateAnswer}, easily the slowest step - while {@code
   * ChatService#appendTurn} afterwards needed a <em>second</em>, independently held connection to
   * write. Under N concurrent persisted-chat queries with Hikari's default pool size of 10, once N
   * reached 10 every caller's outer transaction had claimed a connection and was waiting on the LLM
   * response, and no {@code appendTurn} call could obtain the second connection it needed - a full
   * pool deadlock, not merely contention, and reachable by ordinary chat traffic. Without an
   * ambient transaction here, every repository/service call below (each individually
   * {@code @Transactional} via Spring Data or its own explicit demarcation - see {@code
   * ChatService#appendTurn}'s Javadoc for its own, retry-capable one) is a short-lived,
   * independently connection-scoped call that releases its connection immediately, exactly like
   * {@code UserService.findOrCreateUser}.
   */
  public QueryResult query(
      String question,
      UUID chatId,
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
                        .orElseThrow(() -> new UnauthorizedException("Benutzer nicht gefunden"));

                Optional<Chat> chat = chatService.findOwnedChat(chatId, currentUserId);
                // #525 review, finding 4: querying is chatting, and chatting requires space
                // membership even for an author who already owns the chat - see
                // ChatService#requireStillSpaceMember's Javadoc for why this check lives only on
                // this path and not on getChat/updateChat/deleteChat.
                chat.ifPresent(chatService::requireStillSpaceMember);
                // #840: an archived space accepts no new content (see
                // ChatService#requireSpaceNotArchived's Javadoc) - checked here, before
                // retrieval/the LLM call, so the ordinary case ("space was already archived")
                // never pays for a paid LLM call whose answer appendTurn below would discard
                // anyway. appendTurn's own call to the same guard stays in place as the race
                // guard for a space archived after this point but before the turn is persisted.
                chat.ifPresent(c -> chatService.requireSpaceNotArchived(c.getSpaceId()));
                // A chatId that does not resolve to an owned persisted chat (including "none
                // given") still runs ephemerally rather than being rejected - the pre-#525
                // behaviour, preserved for callers that have not moved to persisted chats yet
                // (see #527). It is reused as the in-memory conversation-cache key when the
                // caller supplied one, exactly as the old free-form conversationId was, so a
                // client round-tripping the previous response's chatId still gets multi-turn
                // continuity without ever persisting anything.
                //
                // #525 review, finding 3 (critical): always qualified with currentUserId. Without
                // this, a chatId that does not resolve to an owned chat (a genuinely unknown id, or
                // - the actual leak - another user's real chat id) would use the bare chatId as the
                // cache key, which for a real chat is the exact key its owner's own persisted-chat
                // path also uses (see below) - a second user supplying it would read the first
                // user's conversation history straight into their own prompt, and their own message
                // would then be appended into the first user's cache entry. Qualifying every key
                // this way, not only the fallback branch, keeps the persisted-chat and ephemeral
                // cases using the same key for the same (user, chat) pair while making it
                // structurally impossible for two different users to ever collide on one key.
                UUID effectiveChatId =
                    chat.map(Chat::getId)
                        .orElseGet(() -> chatId != null ? chatId : UUID.randomUUID());
                String conversationKey = currentUserId + ":" + effectiveChatId;
                seedConversationMemoryFromPersistedHistory(chat, conversationKey);

                String searchQuery = buildSearchQuery(question, conversationKey);

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

                // A persisted chat's own settings govern the scope entirely (#525); only an
                // ephemeral query (no owned chat) falls back to the request-level useKnowledge/
                // requestedLibraryIds #526 introduced.
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
                // #706 review, finding 3: distinct from answeredWithoutKnowledge above - this is
                // the #203 fail-open case where the chip stays on @Alles-Wissen (the caller never
                // chose "ohne Wissen") but the chat's space is curated and none of its associated
                // libraries are readable by this caller, so effectiveLibraryScope legitimately
                // resolves to empty. Only meaningful for a persisted chat - an ephemeral query's
                // empty searchScope instead means the caller simply has no readable library at
                // all, unrelated to curation. See ChatService#spaceHasLibraryAssociations's own
                // Javadoc.
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

                // #561 review, finding 2: appendTurn no longer mutates the `chat` instance loaded
                // above in place - its title/title_source writes go through atomic, targeted
                // ChatRepository updates instead (see that method's Javadoc), so this `chat`
                // reference would otherwise be stale here. appendTurn returns the chat's title as
                // committed by its own atomic update - the fallback title on a first turn, never
                // the LLM-derived title, which (deliberately, see ChatTitleGenerationService's
                // Javadoc) generates asynchronously after this response is built.
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
   * #238's regression check - see {@link #query}'s Javadoc. {@code readableScope} is the full set
   * {@link LibraryAccessService#readableLibraryIds} computed for this query at {@code asOf} - not
   * necessarily the narrower {@code searchScope} #525/#526 may actually hand to the vector store,
   * since either can restrict the search to a subset of what is merely readable. Any id in {@code
   * readableScope} the permission history does not also grant as of {@code asOf} is a mismatch,
   * logged as a single warning per query (not once per offending library - code review of #427, nit
   * 2), never silently ignored. {@code asOf} is the instant {@code readableScope} was itself
   * computed at, not a fresh {@code Instant.now()} taken here - reusing it avoids a false-positive
   * mismatch from a permission change landing in the gap between the two computations.
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
   * metadata is missing or empty (PR #745 review, nit 1) - a chunk without {@code document_id} can
   * only occur for pre-#739 index entries, since {@code FileProcessingService#storeChunks} now
   * writes it on every chunk. {@link #countMatchesPerDocument} previously defaulted to the literal
   * string {@code "unknown"} while {@link #mapSources} read the count back with {@code ""}, so the
   * lookup always missed and {@code matchCount} silently fell back to {@code 1}. Falling back to
   * the same {@code file_name} in both places also keeps two such chunks from <em>different</em>
   * documents from collapsing into one merged entry, which the previous shared empty-string key
   * did.
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
   * points at a document this answer never actually searched. {@code cited} now only reflects
   * <em>valid</em> citations - an invalid one is never allowed to make an unrelated, merely
   * pattern-matching citation count as a genuine one.
   *
   * <p>#697 review, finding 4: the synthetic entries are deliberately <b>not</b> run through the
   * same file-name merge as the real, retrieved-chunk entries. A fabricated citation can coincide
   * in file name with a real, retrieved document (the model routinely copies the correct name even
   * for a fabricated id), and merging would have let the fabricated citation's {@code cited =
   * true}, relevance score and "open in document" link overwrite the real entry's own values - the
   * real document then appeared cited, or more relevant than it is, for a citation it was never
   * actually named in.
   *
   * <p>#697 second review round: dropping the synthetic entry silently on a collision (rather than
   * merging it) traded that problem for another - two {@link ChatSource} rows sharing one {@code
   * fileName} in the response, which {@code frontend/src/components/chat/citations.ts} joins to the
   * answer text purely by file name and resolves last-wins, i.e. always to the synthetic,
   * zero-relevance row. A genuinely, validly cited real source would then have displayed with "0%"
   * relevance and no document link - the exact opposite failure from finding 4, now hitting a
   * <em>valid</em> citation instead of an invalid one. The fix folds a colliding synthetic entry
   * into the real one instead of adding a second row: only {@code citationValid} is set to {@code
   * false} on the real entry; its {@code cited}, relevance score, match count and document link are
   * left exactly as the real, retrieved chunk(s) determined them. A synthetic entry is only ever
   * appended as an extra row when no real entry shares its file name.
   *
   * <p>#739: the real entries below are deduped by {@code document_id}, not {@code fileName} - two
   * distinct documents that happen to share a file name (e.g. two RSS entries both attaching a
   * same-named PDF) now each keep their own {@link ChatSource} row instead of collapsing into one,
   * since {@code fileName} is no longer a reliable proxy for document identity now that every entry
   * also carries its own {@code documentId} deep link. The orphan-collision check below still
   * matches by {@code fileName} deliberately - a fabricated citation naming the right file name but
   * the wrong document id must still flag every real entry sharing that file name, since there is
   * no other signal to tell which one the model meant.
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

    // Keyed on #chunkGroupingKey, not the parsed ChatSource#getDocumentId() (which is null for
    // a malformed/missing value, #739's parseDocumentId) - two chunks with the same unparseable id
    // must still merge into one entry rather than every one of them colliding on a shared null key,
    // and the file_name fallback (PR #745 review, nit 1) keeps two document_id-less chunks from
    // different files from merging into one either.
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
   * Merges duplicate source references for the same <b>document</b> (#739, previously the same file
   * name - see below), keeping the one with the highest relevance score while preserving citation
   * status. If either reference was cited in the answer, the merged result is marked as cited —
   * because any chunk from that document being cited means the document as a whole contributed to
   * the answer.
   *
   * <p>#739: the dedupe key is now {@code document_id}, not {@code fileName} (#639's original
   * reasoning for the opposite choice, below, is why this needed to change deliberately rather than
   * incidentally). Two distinct documents that happen to share a file name (e.g. two RSS entries
   * both attaching a same-named PDF) no longer collapse into one {@link ChatSource} row at all -
   * each keeps its own entry, since #739 needs every entry's own {@code documentId} for its deep
   * link and folding two different documents together would have to pick (or drop) one arbitrarily.
   * {@code a} and {@code b} passed to this method therefore always share the same {@code
   * document_id}, hence the same underlying {@link io.opaa.indexing.Document} row - {@code
   * documentId}, {@code sourceType}, {@code sourceUrl} and {@code sourceEntryUrl} are consequently
   * always equal between them (unlike under the old fileName key, where two genuinely different
   * documents could disagree on {@code sourceEntryUrl} - the reason #639 originally dropped it to
   * {@code null} on any disagreement rather than picking either side). The merge below still reads
   * whichever side happens to be {@code preferred}, since both sides agree anyway.
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
