package io.opaa.query;

import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.AnswerEvidence;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatAttachmentContext;
import io.opaa.chat.ChatNoteExtractionService;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatNoteService;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.chat.SearchScopeResolver;
import io.opaa.chat.UsedPrompt;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.llm.AnswerCompleteness;
import io.opaa.llm.ChatResponses;
import io.opaa.llm.NoActiveChatModelException;
import io.opaa.metadata.MetadataFilter;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.observability.QueryEmbeddingTime;
import io.opaa.observability.QueryMetrics;
import io.opaa.prompt.Prompt;
import io.opaa.prompt.PromptService;
import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.answer.AnswerReaderGoneException;
import io.opaa.query.answer.AnswerStreamListener;
import io.opaa.query.answer.ConversationWindowMessages;
import io.opaa.query.citation.AnswerEvidenceAssessor;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.query.citation.CitationMarkerRepair;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.RetrievalContextFactory;
import io.opaa.retrieval.RetrievalPipeline;
import io.opaa.retrieval.RetrievalPipelineResult;
import io.opaa.retrieval.context.NeighborChunkExpansion;
import io.opaa.retrieval.search.SubQueryDecompositionStage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Answers one question end to end (docs/handbuch/referenz/wie-die-suche-arbeitet.md): resolves the
 * search scope, runs the {@link RetrievalPipeline} over it, has the answer generated, validates the
 * citations and has the source rows built.
 *
 * <p>A query always reads with the calling user's own rights - no system-admin bypass, no second
 * rights context - and the permission filter is part of every search query itself, the {@code
 * VectorChunkSearch#similaritySearch} call and the full-text query alike, never a post-filter, so
 * an unauthorized chunk is never loaded or ranked. An empty search scope short-circuits to answer
 * generation with zero chunks, the same path a genuinely empty result takes, so the answer cannot
 * distinguish "no permission on anything" from "nothing matched".
 *
 * <p>A message the decomposition found nothing to search for is searched like any other; only the
 * answer is told, so that it does not claim to have found nothing. That signal depends on the
 * message alone, never on what the caller may read.
 */
@Service
public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  private final KnowledgeRetrieval knowledgeRetrieval;
  private final RetrievalContextFactory retrievalContextFactory;
  private final SearchScopeResolver searchScopeResolver;
  private final ChatSourceAssembler chatSourceAssembler;
  private final AnswerGenerationService answerGenerationService;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final LibraryAccessService libraryAccessService;
  private final ChatService chatService;
  private final ChatNoteService chatNoteService;
  private final ChatNoteExtractionService chatNoteExtractionService;
  private final QueryMetrics metrics;
  private final MetadataFilterValidator metadataFilterValidator;
  private final PromptService promptService;
  private final Optional<ToolLoopQueryHandler> toolLoopQueryHandler;
  private final NeighborChunkExpansion neighborChunkExpansion;
  private final AgentTurnHandler agentTurnHandler;

  public QueryService(
      KnowledgeRetrieval knowledgeRetrieval,
      RetrievalContextFactory retrievalContextFactory,
      SearchScopeResolver searchScopeResolver,
      ChatSourceAssembler chatSourceAssembler,
      AnswerGenerationService answerGenerationService,
      ChatMemory chatMemory,
      CitationParser citationParser,
      CitationValidator citationValidator,
      LibraryAccessService libraryAccessService,
      ChatService chatService,
      ChatNoteService chatNoteService,
      ChatNoteExtractionService chatNoteExtractionService,
      QueryMetrics metrics,
      MetadataFilterValidator metadataFilterValidator,
      PromptService promptService,
      Optional<ToolLoopQueryHandler> toolLoopQueryHandler,
      NeighborChunkExpansion neighborChunkExpansion,
      AgentTurnHandler agentTurnHandler) {
    this.knowledgeRetrieval = knowledgeRetrieval;
    this.retrievalContextFactory = retrievalContextFactory;
    this.searchScopeResolver = searchScopeResolver;
    this.chatSourceAssembler = chatSourceAssembler;
    this.answerGenerationService = answerGenerationService;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.libraryAccessService = libraryAccessService;
    this.chatService = chatService;
    this.chatNoteService = chatNoteService;
    this.chatNoteExtractionService = chatNoteExtractionService;
    this.metrics = metrics;
    this.metadataFilterValidator = metadataFilterValidator;
    this.promptService = promptService;
    this.toolLoopQueryHandler = toolLoopQueryHandler;
    this.neighborChunkExpansion = neighborChunkExpansion;
    this.agentTurnHandler = agentTurnHandler;
  }

  /**
   * Answers {@code question} against the libraries {@code caller} may read.
   *
   * <p>A chat {@code chatId} names and {@code caller} authored governs the turn entirely: search
   * scope and filter come from the chat's own settings - {@code useKnowledge} and {@code
   * requestedLibraryIds} are then ignored, not merely defaulted - the conversation window is read
   * from its persisted history on every turn, and question and answer are persisted. Otherwise the
   * query runs ephemerally and, having no space, searches no knowledge at all: {@code useKnowledge}
   * and {@code requestedLibraryIds} widen nothing, and the answer is marked {@code noSpaceContext}.
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
    return query(
        question, chatId, caller, useKnowledge, requestedLibraryIds, requestedMetadataFilter, null);
  }

  /**
   * The same query, built from the prompt {@code usedPromptId} names ({@code null}: none). The
   * question arrives already resolved - the prompt changes nothing about retrieval or the model
   * call. It is checked before anything is paid for: a prompt the caller may not read, or one not
   * associated with the chat's space, refuses the turn with {@code 403}. A persisted chat keeps its
   * id and current title on the question's message; no audit entry is written.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter,
      UUID usedPromptId) {
    return query(
        question,
        chatId,
        caller,
        useKnowledge,
        requestedLibraryIds,
        requestedMetadataFilter,
        usedPromptId,
        null);
  }

  /**
   * The same query, with the answer told to {@code answerListener} while the model writes it
   * ({@code null}: not streamed, see {@link AnswerGenerationService#streamAnswer}). Everything
   * after the generation - citation repair and validation, sources, persistence, the note - runs on
   * the complete answer exactly as without streaming, and only once it is complete: a stream that
   * ends early persists nothing. A turn the tool loop takes over is not streamed.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter,
      UUID usedPromptId,
      AnswerStreamListener answerListener) {
    return query(
        question,
        chatId,
        caller,
        useKnowledge,
        requestedLibraryIds,
        requestedMetadataFilter,
        usedPromptId,
        answerListener,
        List.of());
  }

  /**
   * The same query with the chat attachments {@code attachmentIds} sent along. Their passages - and
   * those of every earlier attachment of the chat - precede the retrieved chunks and are cited like
   * them ({@code ChatService#attachmentContext}); the attachments are bound to the chat with the
   * turn. Naming one without a persisted chat of the caller is a 400. A turn with attachments is
   * never taken over by the tool loop, which would not see them.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter,
      UUID usedPromptId,
      AnswerStreamListener answerListener,
      List<UUID> attachmentIds) {
    return query(
        question,
        chatId,
        caller,
        useKnowledge,
        requestedLibraryIds,
        requestedMetadataFilter,
        usedPromptId,
        answerListener,
        attachmentIds,
        null);
  }

  /**
   * The same query with the agent {@code agentId} chosen ({@code null}: none). A turn with an agent
   * runs through the {@link AgentTurnHandler} instead of the ordinary path (ADR-0070): it needs a
   * persisted chat of the caller and takes no new attachments (400 otherwise), and it is not
   * streamed. Without an agent nothing changes - one search, one model call, no tool definitions.
   */
  public QueryResult query(
      String question,
      UUID chatId,
      CurrentUser caller,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter,
      UUID usedPromptId,
      AnswerStreamListener answerListener,
      List<UUID> attachmentIds,
      UUID agentId) {
    UUID currentUserId = caller.id();
    List<UUID> requestedAttachments = attachmentIds == null ? List.of() : attachmentIds;
    QueryClock clock = new QueryClock();
    return measured(
        clock,
        () -> {
          // --- Read phase: membership/archive/scope checks and the retrieval below all
          // run without any ambient transaction of their own - each repository call opens
          // and releases its own short-lived connection.
          Optional<Chat> chat = chatService.findOwnedChat(chatId, currentUserId);
          // Querying is chatting: requires space membership even for an author who
          // already owns the chat - see ChatService#requireStillSpaceMember.
          clock.mode =
              chat.filter(Chat::isUseKnowledge).isPresent()
                  ? QueryMetrics.Mode.RAG
                  : QueryMetrics.Mode.NO_SEARCH;
          chat.ifPresent(chatService::requireStillSpaceMember);
          // An archived space accepts no new content - checked before the LLM call, so
          // no answer is paid for that appendTurn would discard. appendTurn's own call to
          // the same guard remains the race guard for a space archived after this point.
          chat.ifPresent(c -> chatService.requireSpaceNotArchived(c.getSpaceId()));
          UsedPrompt usedPrompt = usedPrompt(usedPromptId, chat, caller);
          if (chat.isEmpty() && !requestedAttachments.isEmpty()) {
            throw new ValidationException(
                "Anhänge lassen sich nur in einem eigenen Chat mitsenden");
          }
          ChatAttachmentContext attachments =
              chat.map(c -> chatService.attachmentContext(c, requestedAttachments, question))
                  .orElse(ChatAttachmentContext.NONE);
          // A chatId that does not resolve to an owned persisted chat (including "none
          // given") runs ephemerally rather than being rejected, reused as the in-memory
          // conversation-cache key when the caller supplied one, or freshly generated
          // otherwise. Always qualified with currentUserId: without this, an unresolved
          // chatId (unknown, or another user's real chat id) would collide with the cache
          // key that chat's owner's own persisted-chat path uses, leaking one user's
          // conversation history into another's prompt.
          UUID effectiveChatId =
              chat.map(Chat::getId).orElseGet(() -> chatId != null ? chatId : UUID.randomUUID());
          String conversationKey = currentUserId + ":" + effectiveChatId;
          boolean historyCut = seedConversationMemoryFromPersistedHistory(chat, conversationKey);

          // The Gesprächsnotiz as it stands *before* this turn - the state that goes into
          // this answer and is returned with it. The condensation of this turn's own question
          // runs after the answer, below.
          List<ChatNotePoint> notePoints =
              chat.map(c -> chatNoteService.points(c.getId())).orElse(null);
          // The space's instruction to the model goes to the answer only, never to the
          // decomposition - a rule of form or framing has no place in a search query.
          String spaceInstruction =
              chat.map(c -> chatService.spaceModelInstruction(c.getSpaceId())).orElse(null);

          // Before the decomposition call in the non-empty-scope branch below, so
          // durationMs includes its latency rather than silently excluding it.
          long startTime = System.currentTimeMillis();

          Set<UUID> readableLibraryIds =
              libraryAccessService.readableLibraryIds(currentUserId, caller.organizationId());

          // A persisted chat's own settings govern the scope entirely; a question without a
          // chat of the caller has no space and searches nothing.
          Set<UUID> searchScope = searchScopeResolver.resolveSearchScope(chat, readableLibraryIds);
          MetadataFilter metadataFilter =
              validatedMetadataFilter(
                  readableLibraryIds,
                  chat.map(Chat::getMetadataFilter)
                      .orElse(
                          requestedMetadataFilter == null
                              ? MetadataFilter.NONE
                              : requestedMetadataFilter));

          clock.mode =
              searchScope.isEmpty() && attachments.isEmpty()
                  ? QueryMetrics.Mode.NO_SEARCH
                  : QueryMetrics.Mode.RAG;

          if (agentId != null) {
            if (chat.isEmpty()) {
              throw new ValidationException("Einen Agenten gibt es nur in einem eigenen Chat");
            }
            if (!attachments.newAttachmentIds().isEmpty()) {
              throw new ValidationException(
                  "Anhänge lassen sich einem Agenten noch nicht mitsenden");
            }
            clock.mode = QueryMetrics.Mode.AGENT;
            return agentTurnHandler.handle(
                new AgentTurn(
                    question,
                    chat.get(),
                    caller.organizationId(),
                    currentUserId,
                    readableLibraryIds,
                    agentId,
                    conversationKey,
                    notePoints,
                    spaceInstruction,
                    usedPrompt,
                    metadataFilter,
                    startTime,
                    answerListener));
          }

          // A present tool-loop handler may take over the whole turn, with exactly the
          // rights and scope just resolved above; an empty result leaves everything below
          // unchanged. A question that names new attachments stays on the ordinary path,
          // which reads them; earlier attachments of the chat, the loop's own generated
          // files among them, do not keep a turn from the loop.
          Optional<QueryResult> toolLoopResult =
              toolLoopQueryHandler
                  .filter(handler -> attachments.newAttachmentIds().isEmpty())
                  .flatMap(
                      handler -> {
                        QueryMetrics.Mode ordinaryMode = clock.mode;
                        clock.mode = QueryMetrics.Mode.AGENT;
                        Optional<QueryResult> taken =
                            handler.handle(
                                question,
                                chat,
                                effectiveChatId,
                                conversationKey,
                                notePoints,
                                searchScope,
                                metadataFilter,
                                startTime,
                                usedPrompt);
                        if (taken.isEmpty()) {
                          clock.mode = ordinaryMode;
                        }
                        return taken;
                      });
          if (toolLoopResult.isPresent()) {
            if (historyCut) {
              metrics.recordContextTruncated();
            }
            return toolLoopResult.get();
          }

          // Why an empty scope is empty - at most one of the four: no space at all, nothing
          // associated with the space, associated but nothing of it readable by this
          // caller, or a chip bar emptied on purpose.
          boolean noSpaceContext = chat.isEmpty();
          boolean effectiveUseKnowledge = chat.map(Chat::isUseKnowledge).orElse(false);
          // Attachments are knowledge the person brought: an answer from them is none of
          // these three cases.
          boolean noAttachments = attachments.isEmpty();
          boolean noKnowledgeAssignedToSpace =
              noAttachments
                  && searchScope.isEmpty()
                  && chat.map(
                          c ->
                              !chatService.spaceHasLibraryAssociations(
                                  c.getSpaceId(), readableLibraryIds, caller.organizationId()))
                      .orElse(false);
          boolean answeredWithoutKnowledge =
              !noSpaceContext
                  && !noKnowledgeAssignedToSpace
                  && !effectiveUseKnowledge
                  && searchScope.isEmpty()
                  && noAttachments;
          boolean noKnowledgeAvailableInSpace =
              !noKnowledgeAssignedToSpace
                  && effectiveUseKnowledge
                  && searchScope.isEmpty()
                  && chat.isPresent()
                  && noAttachments;

          // The decomposition LLM call only runs once there is actually something to
          // search - an empty scope would otherwise pay for it and discard the result.
          List<Document> relevantChunks;
          boolean searchNeeded = true;
          List<String> searchQueries = List.of();
          Map<String, Set<Integer>> subQueriesByDocument = Map.of();
          if (searchScope.isEmpty()) {
            relevantChunks = List.of();
          } else {
            long retrievalStarted = System.nanoTime();
            QueryEmbeddingTime.Measured<RetrievalPipelineResult> measuredRetrieval =
                QueryEmbeddingTime.measure(
                    () ->
                        retrieve(
                            question,
                            chatMemory.get(conversationKey),
                            noteTexts(notePoints, ChatNoteItemKind.RAHMEN),
                            searchScope,
                            metadataFilter));
            RetrievalPipelineResult retrieval = measuredRetrieval.value();
            relevantChunks = neighborChunkExpansion.expand(retrieval.chunks(), searchScope);
            recordRetrievalStages(
                retrieval,
                measuredRetrieval.embedding(),
                Duration.ofNanos(System.nanoTime() - retrievalStarted));
            metrics.recordRetrieval(retrieval.chunks().size());
            searchNeeded = retrieval.searchNeeded();
            searchQueries = retrieval.searchQueries();
            subQueriesByDocument = retrieval.subQueriesByDocument();
          }

          // The chat's attachments come first: they are what the person put in front of the
          // question, and a long retrieval list must not push them out of view.
          if (!attachments.isEmpty()) {
            List<Document> withAttachments = new ArrayList<>(attachments.passages());
            withAttachments.addAll(relevantChunks);
            relevantChunks = withAttachments;
          }
          boolean searched = !searchScope.isEmpty() || !attachments.isEmpty();

          // --- LLM call: the slowest step, and the reason no phase of this method
          // carries a transaction.
          long generationStarted = System.nanoTime();
          AnswerStreamListener timedListener =
              answerListener == null ? null : clock.timeToFirstToken(answerListener);
          ChatResponse chatResponse =
              answerListener == null
                  ? answerGenerationService.generateAnswer(
                      question,
                      relevantChunks,
                      conversationKey,
                      noteTexts(notePoints, null),
                      searched,
                      searchNeeded,
                      spaceInstruction)
                  : answerGenerationService.streamAnswer(
                      question,
                      relevantChunks,
                      conversationKey,
                      noteTexts(notePoints, null),
                      searched,
                      searchNeeded,
                      spaceInstruction,
                      timedListener);
          metrics.recordStage(
              QueryMetrics.Stage.GENERATE, Duration.ofNanos(System.nanoTime() - generationStarted));

          String answer =
              CitationMarkerRepair.repair(ChatResponses.text(chatResponse), relevantChunks);
          List<CitationValidator.ValidatedCitation> validatedCitations =
              citationValidator.validate(
                  citationParser.extractCitations(answer), relevantChunks, answer);
          List<ChatSource> sources =
              chatSourceAssembler.assemble(relevantChunks, validatedCitations, metadataFilter);
          markAttachments(sources, attachments);

          log.debug(
              "Citations found: {} validated, {} total sources",
              validatedCitations.size(),
              sources.size());

          long durationMs = System.currentTimeMillis() - startTime;
          String model = ChatResponses.model(chatResponse);
          int tokenCount = ChatResponses.totalTokens(chatResponse);
          AnswerCompleteness completeness = ChatResponses.completeness(chatResponse);
          if (completeness != AnswerCompleteness.COMPLETE) {
            log.warn(
                "Answer of model '{}' is {} after {} tokens - for TRUNCATED the output"
                    + " limit was reached, the model's maximum response length may be too"
                    + " small",
                model,
                completeness,
                tokenCount);
          }

          metrics.recordContextTokens(ChatResponses.inputTokens(chatResponse));
          metrics.recordFinishReason(ChatResponses.finishReason(chatResponse));
          AnswerEvidence evidence =
              AnswerEvidenceAssessor.assess(
                  validatedCitations,
                  searched,
                  !relevantChunks.isEmpty(),
                  searchNeeded,
                  searchQueries,
                  subQueriesByDocument,
                  completeness);
          metrics.recordEvidence(evidence == null ? null : evidence.level());

          // --- Write phase: the one place this method's result is persisted, in
          // ChatService#appendTurn's own transaction(s). appendTurn's return value, not the
          // `chat` instance loaded above, is the source of truth for the title - the
          // fallback title on a first turn, never the LLM-derived one, which is generated
          // asynchronously after this response is built.
          if (answerListener != null) {
            try {
              answerListener.beforeKeeping();
            } catch (IOException readerGone) {
              throw new AnswerReaderGoneException(readerGone);
            }
          }
          long persistStarted = System.nanoTime();
          String chatTitle =
              chat.map(
                      c ->
                          attachments.newAttachmentIds().isEmpty()
                              ? chatService.appendTurn(
                                  c, question, usedPrompt, answer, sources, evidence)
                              : chatService.appendTurn(
                                  c,
                                  question,
                                  usedPrompt,
                                  answer,
                                  sources,
                                  evidence,
                                  attachments.newAttachmentIds()))
                  .orElse(null);
          if (chat.isPresent()) {
            metrics.recordStage(
                QueryMetrics.Stage.PERSIST, Duration.ofNanos(System.nanoTime() - persistStarted));
          }
          // The condensation of this turn's question, off the request thread and only once
          // the turn is durably persisted - triggered here rather than inside appendTurn so
          // the chat package's note services stay free of a dependency on ChatService, which
          // needs the note itself for every chat it returns.
          chat.ifPresent(
              c -> chatNoteExtractionService.condenseAsync(c.getId(), c.getSpaceId(), question));

          QueryOutcome metadata =
              new QueryOutcome(
                  model,
                  tokenCount,
                  durationMs,
                  answeredWithoutKnowledge,
                  noKnowledgeAssignedToSpace,
                  noKnowledgeAvailableInSpace,
                  noSpaceContext,
                  chatSourceAssembler.searchedLibraries(searchScope),
                  completeness);
          if (historyCut) {
            metrics.recordContextTruncated();
          }
          return new QueryResult(
              answer, sources, metadata, effectiveChatId, chatTitle, notePoints, evidence);
        });
  }

  /**
   * Runs one question and records it in {@code opaa.query.*} under the mode {@code clock} holds
   * when it ends: answered or failed, its duration, and for a failure why it ended without an
   * answer.
   */
  private QueryResult measured(QueryClock clock, Supplier<QueryResult> question) {
    try {
      QueryResult result = question.get();
      metrics.recordQuery(clock.mode, true, clock.elapsed());
      return result;
    } catch (RuntimeException failure) {
      if (isRejection(failure)) {
        metrics.recordRejected(clock.mode);
      } else {
        metrics.recordQuery(clock.mode, false, clock.elapsed());
        metrics.recordAborted(
            failure instanceof AnswerReaderGoneException
                ? QueryMetrics.AbortCause.CLIENT_CANCEL
                : QueryMetrics.abortCause(failure));
      }
      throw failure;
    }
  }

  /**
   * A request error rather than a failure of the service: invalid, not permitted, not found, an
   * archived space, or no chat model set up. Counted as rejected, outside the chat SLO.
   */
  private static boolean isRejection(RuntimeException failure) {
    return failure instanceof ValidationException
        || failure instanceof FieldValidationException
        || failure instanceof AccessDeniedException
        || failure instanceof UnauthorizedException
        || failure instanceof NotFoundException
        || failure instanceof ConflictException
        || failure instanceof PayloadTooLargeException
        || failure instanceof TooManyRequestsException
        || failure instanceof NoActiveChatModelException;
  }

  /**
   * The retrieval's share of the stages: decomposition and rerank as the pipeline measured them,
   * the query embedding as {@link QueryEmbeddingTime} summed it, and the rest of {@code total} -
   * searches, fusion, selection and the neighbor expansion - as {@code retrieve}.
   */
  private void recordRetrievalStages(
      RetrievalPipelineResult retrieval, Duration embedding, Duration total) {
    Duration decompose = stageDuration(retrieval, RetrievalStageName.SUB_QUERY_DECOMPOSITION);
    Duration rerank = stageDuration(retrieval, RetrievalStageName.RERANK);
    Duration rest = total.minus(embedding);
    if (decompose != null) {
      metrics.recordStage(QueryMetrics.Stage.DECOMPOSE, decompose);
      rest = rest.minus(decompose);
    }
    if (!embedding.isZero()) {
      metrics.recordStage(QueryMetrics.Stage.EMBED_QUERY, embedding);
    }
    if (rerank != null) {
      metrics.recordStage(QueryMetrics.Stage.RERANK, rerank);
      rest = rest.minus(rerank);
    }
    metrics.recordStage(QueryMetrics.Stage.RETRIEVE, rest);
  }

  /** The duration the pipeline stamped on {@code stage}, {@code null} when it did not run. */
  private static Duration stageDuration(
      RetrievalPipelineResult retrieval, RetrievalStageName stage) {
    if (retrieval.explanation() == null) {
      return null;
    }
    return retrieval.explanation().stages().stream()
        .filter(explanation -> explanation.stage() == stage && explanation.durationMs() != null)
        .map(explanation -> Duration.ofMillis(explanation.durationMs()))
        .findFirst()
        .orElse(null);
  }

  /** When one question started and in which {@link QueryMetrics.Mode} it currently runs. */
  private final class QueryClock {

    private final long startedNanos = System.nanoTime();
    private QueryMetrics.Mode mode = QueryMetrics.Mode.NO_SEARCH;

    Duration elapsed() {
      return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    /**
     * {@code listener}, recording the time to its first {@code provisional} call - the first text
     * the reader sees; a leading reasoning block the stream holds back does not count.
     */
    AnswerStreamListener timeToFirstToken(AnswerStreamListener listener) {
      QueryMetrics.Mode streamedMode = mode;
      return new AnswerStreamListener() {
        private boolean visible;

        @Override
        public void started() throws IOException {
          listener.started();
        }

        @Override
        public void provisional(String text, boolean replace) throws IOException {
          if (!visible) {
            visible = true;
            metrics.recordTimeToFirstToken(streamedMode, elapsed());
          }
          listener.provisional(text, replace);
        }

        @Override
        public void idle() throws IOException {
          listener.idle();
        }

        @Override
        public void beforeKeeping() throws IOException {
          listener.beforeKeeping();
        }
      };
    }
  }

  /** Marks the sources that come from one of the chat's attachments. */
  private static void markAttachments(List<ChatSource> sources, ChatAttachmentContext attachments) {
    if (attachments.attachmentIds().isEmpty()) {
      return;
    }
    for (ChatSource source : sources) {
      if (source.getDocumentId() != null
          && attachments.attachmentIds().contains(source.getDocumentId())) {
        source.attachment(true);
      }
    }
  }

  /**
   * A prompt is usable only in a space it is associated with; an ephemeral query has no space and
   * therefore offers no prompt at all.
   */
  private UsedPrompt usedPrompt(UUID usedPromptId, Optional<Chat> chat, CurrentUser caller) {
    if (usedPromptId == null) {
      return null;
    }
    Prompt prompt =
        promptService.requireUsable(usedPromptId, chat.map(Chat::getSpaceId).orElse(null), caller);
    return new UsedPrompt(prompt.getId(), prompt.getTitle());
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
   * The note's point texts, optionally narrowed to one kind: {@link ChatNoteItemKind#RAHMEN} for
   * the sub-question decomposition, {@code null} for the answer, which sees every point (ADR-0031).
   * Empty outside a persisted chat.
   */
  private static List<String> noteTexts(List<ChatNotePoint> points, ChatNoteItemKind onlyKind) {
    if (points == null) {
      return List.of();
    }
    return points.stream()
        .filter(point -> onlyKind == null || point.kind() == onlyKind)
        .map(ChatNotePoint::text)
        .toList();
  }

  /**
   * Replaces the conversation window of a persisted chat with its persisted history on every turn
   * (ADR-0053, Entscheidung 10) - the mechanism that makes {@link SubQueryDecompositionStage} and
   * {@link AnswerGenerationService#generateAnswer} see the history without either reading the
   * database directly. {@code chat_messages} is the only source: a turn another backend instance
   * answered is in the window even when this process still holds an older one. An ephemeral
   * conversation has nothing persisted and keeps its process-local window (ADR-0053).
   *
   * <p>This is the <b>second entrance into the conversation window</b>; it normalizes through the
   * same {@link ConversationWindowMessages} the first one uses, which is what makes the same chat
   * send the same prompt before and after a restart, a cache eviction or a change of instance.
   *
   * @return whether the chat holds more messages than the window, so older ones no longer reach the
   *     model.
   */
  private boolean seedConversationMemoryFromPersistedHistory(
      Optional<Chat> chat, String conversationKey) {
    if (chat.isEmpty()) {
      return false;
    }
    int window = retrievalContextFactory.queryProperties().conversationWindowMessages();
    // One message more than the window tells whether the chat holds more than fits.
    List<Message> loaded = chatService.historyAsSpringAiMessages(chat.get().getId(), window + 1);
    List<Message> persistedHistory =
        loaded.size() > window ? loaded.subList(loaded.size() - window, loaded.size()) : loaded;
    chatMemory.clear(conversationKey);
    if (!persistedHistory.isEmpty()) {
      chatMemory.add(conversationKey, ConversationWindowMessages.reloaded(persistedHistory));
    }
    return loaded.size() > window;
  }

  /**
   * The retrieval half of {@link #query}, run through the {@link KnowledgeRetrieval} every
   * caller-facing retrieval goes through - the same pipeline, the same parameters and the same
   * permission filter {@code POST /api/v1/search} uses.
   */
  private RetrievalPipelineResult retrieve(
      String question,
      List<Message> conversationHistory,
      List<String> conversationNote,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter) {
    return knowledgeRetrieval.retrieve(
        question, conversationHistory, conversationNote, searchScope, metadataFilter);
  }
}
