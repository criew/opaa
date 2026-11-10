package io.opaa.query.toolloop;

import io.micrometer.observation.ObservationRegistry;
import io.opaa.chat.AgentRunRecord;
import io.opaa.chat.AgentRunRecord.EndState;
import io.opaa.chat.AnswerEvidence;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatNoteExtractionService;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.common.ConflictException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.compliance.DataFlow;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.AnswerCompleteness;
import io.opaa.llm.ChatResponses;
import io.opaa.llm.ToolCallingNotCapableException;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.AgentTurn;
import io.opaa.query.AgentTurnHandler;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.answer.ActiveAnswerStreams;
import io.opaa.query.answer.AnswerReaderGoneException;
import io.opaa.query.answer.AnswerStreamListener;
import io.opaa.query.answer.ConversationWindowMessages;
import io.opaa.query.citation.AnswerEvidenceAssessor;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.query.citation.CitationMarkerRepair;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.query.runlog.AgentRunLogWriter;
import io.opaa.retrieval.ChunkGroupingKey;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The tool loop of an agent turn (ADR-0070). One run per request: it takes a place in the {@link
 * AgentWorkspace} before the first model call - a full workspace refuses the request, no run, no
 * partial result -, lets the model call the agent's switched-on tools until it answers or a limit
 * of the run is reached, and gives the place back at the end. A run ended at a limit answers with
 * what it has and a set-off end state, never with an error; a run that asks the person back ends
 * with the question as its answer.
 *
 * <p>The application executes the model's tool calls itself through a {@link ToolCallingManager}
 * rather than leaving them to a {@code ToolCallingAdvisor}: so it counts every call against the
 * run's limits before it runs. Citations are validated against the passages of all steps together:
 * a passage of step two stays citable in step four, an invented one is marked. The run never reads
 * the bestand except through the tools. What a tool opened for the run ({@link RunResource}, e.g. a
 * sandbox) hands its files over before the answer is kept and is closed when the run ends.
 */
@DataFlow("chat-model")
@Service
class AgentToolLoop implements AgentTurnHandler {

  private static final Logger log = LoggerFactory.getLogger(AgentToolLoop.class);

  static final String NOT_AVAILABLE = "Dieser Agent ist nicht verfügbar.";

  static final String NOT_AVAILABLE_CODE = "AGENT_NOT_AVAILABLE";

  static final String WORKSPACE_FULL =
      "Gerade arbeiten zu viele Agenten gleichzeitig. Bitte versuche es in Kürze erneut.";

  static final String WORKSPACE_FULL_CODE = "AGENT_WORKSPACE_FULL";

  /** The answer of a run that reached a limit before the model wrote any text. */
  static final String NO_ANSWER_AT_LIMIT =
      "Der Lauf wurde an einer Grenze beendet, bevor eine Antwort entstand.";

  static final Duration HEARTBEAT = Duration.ofSeconds(10);

  private final ObjectProvider<AgentRunSpecResolver> specResolver;
  private final ObjectProvider<AgentToolSwitches> toolSwitches;
  private final ToolRegistry registry;
  private final AgentToolLoopProperties properties;
  private final AgentWorkspace workspace;
  private final AgentMetrics metrics;
  private final ActiveChatModelResolver chatModelResolver;
  private final ChatService chatService;
  private final ChatMemory chatMemory;
  private final ChatNoteExtractionService chatNoteExtractionService;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final ChatSourceAssembler chatSourceAssembler;
  private final QueryMetrics queryMetrics;
  private final ObservationRegistry observationRegistry;
  private final ObjectMapper objectMapper;
  private final AgentRunLogWriter runLogWriter;
  private final ActiveAnswerStreams activeAnswerStreams;
  private final Clock clock;

  /**
   * Pings the readers of this instance's streamed runs while their run works, one daemon thread for
   * all of them (ADR-0053): per instance on purpose, a ping lives within its request.
   */
  private final ScheduledExecutorService pinger =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("agent-stream-ping").daemon().factory());

  /** Time between two pings of a streamed run, the same as on the ordinary path. */
  Duration heartbeat = HEARTBEAT;

  AgentToolLoop(
      ObjectProvider<AgentRunSpecResolver> specResolver,
      ObjectProvider<AgentToolSwitches> toolSwitches,
      ToolRegistry registry,
      AgentToolLoopProperties properties,
      AgentWorkspace workspace,
      AgentMetrics metrics,
      ActiveChatModelResolver chatModelResolver,
      ChatService chatService,
      ChatMemory chatMemory,
      ChatNoteExtractionService chatNoteExtractionService,
      CitationParser citationParser,
      CitationValidator citationValidator,
      ChatSourceAssembler chatSourceAssembler,
      QueryMetrics queryMetrics,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectMapper objectMapper,
      AgentRunLogWriter runLogWriter,
      ActiveAnswerStreams activeAnswerStreams,
      Clock clock) {
    this.specResolver = specResolver;
    this.toolSwitches = toolSwitches;
    this.registry = registry;
    this.properties = properties;
    this.workspace = workspace;
    this.metrics = metrics;
    this.chatModelResolver = chatModelResolver;
    this.chatService = chatService;
    this.chatMemory = chatMemory;
    this.chatNoteExtractionService = chatNoteExtractionService;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.chatSourceAssembler = chatSourceAssembler;
    this.queryMetrics = queryMetrics;
    this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    this.objectMapper = objectMapper;
    this.runLogWriter = runLogWriter;
    this.activeAnswerStreams = activeAnswerStreams;
    this.clock = clock;
  }

  @Override
  public QueryResult handle(AgentTurn turn) {
    Chat chat = turn.chat();
    AgentRunSpec spec = resolve(turn);
    Instant started = clock.instant();
    ChatClient client;
    try {
      chatModelResolver.resolveToolCallingChatClient();
      // Each model call is bounded, so a reservation cannot expire under a running run.
      client = chatModelResolver.resolveChatClient(properties.closingCallAllowance());
    } catch (ToolCallingNotCapableException e) {
      metrics.recordRun(spec.builtin(), AgentRunResult.MODEL_NOT_CAPABLE, Duration.ZERO);
      throw e;
    }

    AgentRun run =
        new AgentRun(
            turn.organizationId(),
            turn.userId(),
            chat.getId(),
            chat.getSpaceId(),
            scope(spec, chat, turn.readableLibraryIds()),
            turn.metadataFilter(),
            AgentLimits.effective(properties.current(), spec.limits()),
            started);
    if (!workspace.tryReserve(run.runId())) {
      metrics.recordRejected();
      throw new TooManyRequestsException(WORKSPACE_FULL, 0, WORKSPACE_FULL_CODE);
    }
    AgentRunStream stream = null;
    try {
      signal(turn, AnswerStreamListener::started);
      if (turn.stream() != null) {
        stream =
            new AgentRunStream(
                turn.stream(), run.runId(), workspace, activeAnswerStreams, pinger, heartbeat);
        run.listen(stream);
        String cancelToken = UUID.randomUUID().toString();
        workspace.armCancel(run.runId(), turn.userId(), cancelToken);
        signal(turn, reader -> reader.runStarted(run.runId(), cancelToken));
      }
      QueryResult result = runAndKeep(turn, spec, run, client);
      metrics.recordRun(
          spec.builtin(),
          AgentRunResult.of(result.agentRun().endState()),
          Duration.between(started, clock.instant()));
      return result;
    } catch (RuntimeException e) {
      // whatever the interrupted model call threw, the reader is told that the instance stops
      RuntimeException failure =
          stream != null && stream.shuttingDown() ? ActiveAnswerStreams.shuttingDown() : e;
      boolean aborted = failure instanceof AnswerReaderGoneException || failure != e;
      metrics.recordRun(
          spec.builtin(),
          aborted ? AgentRunResult.ABORTED : AgentRunResult.ERROR,
          Duration.between(started, clock.instant()));
      if (!aborted) {
        // the run id is the only key into the step log of a run that left no answer behind
        log.warn("Agent run {} failed: {}", run.runId(), e.getClass().getSimpleName());
      }
      throw failure;
    } finally {
      if (stream != null) {
        stream.close();
      }
      closeResources(run);
      runLogWriter.writeSteps(run.organizationId(), run.runId(), run.trace());
      release(run);
    }
  }

  @PreDestroy
  void stopPinger() {
    pinger.shutdownNow();
  }

  /** Closes what the run's tools opened, e.g. its sandbox; a failure is logged, never thrown. */
  private static void closeResources(AgentRun run) {
    for (RunResource resource : run.resources()) {
      try {
        resource.close();
      } catch (RuntimeException e) {
        log.warn("Could not close a resource of agent run {}", run.runId(), e);
      }
    }
  }

  /** The files the run's tools produced, taken as unbound attachments of the asker. */
  private static List<ChatAttachment> finishResources(AgentRun run) {
    List<ChatAttachment> files = new ArrayList<>();
    for (RunResource resource : run.resources()) {
      try {
        files.addAll(resource.finish());
      } catch (RuntimeException e) {
        // The answer is worth more than its files; the resource's own files stay unbound.
        log.warn("Could not take the files of a resource of agent run {}", run.runId(), e);
      }
    }
    return files;
  }

  /**
   * Gives the place back. A failure here must not replace the run's outcome - the answer is kept
   * already - and the reservation expires on its own.
   */
  private void release(AgentRun run) {
    try {
      workspace.release(run.runId());
    } catch (RuntimeException e) {
      log.warn("Could not release the workspace place of agent run {}", run.runId(), e);
    }
  }

  /**
   * Tells a streamed request that the run is alive: committed once the run holds its place, so a
   * proxy in front does not cut a quiet connection, pinged before each model call, checked before
   * the turn is kept. A reader that went away ends the run and nothing is kept.
   */
  private static void signal(AgentTurn turn, StreamSignal signal) {
    if (turn.stream() == null) {
      return;
    }
    try {
      signal.send(turn.stream());
    } catch (IOException readerGone) {
      throw new AnswerReaderGoneException(readerGone);
    }
  }

  @FunctionalInterface
  private interface StreamSignal {
    void send(AnswerStreamListener stream) throws IOException;
  }

  private AgentRunSpec resolve(AgentTurn turn) {
    AgentRunSpecResolver resolver = specResolver.getIfAvailable();
    Optional<AgentRunSpec> spec =
        resolver == null
            ? Optional.empty()
            : resolver.resolve(
                turn.agentId(),
                turn.organizationId(),
                turn.userId(),
                turn.chat().getSpaceId(),
                turn.readableLibraryIds());
    return spec.orElseThrow(() -> new ConflictException(NOT_AVAILABLE, NOT_AVAILABLE_CODE));
  }

  /**
   * The libraries of the chat's space the caller may read, narrowed to the agent's when it names
   * any (ADR-0070, Entscheidung 3). The chat's own chip bar does not apply: a chosen agent replaces
   * the search scope.
   */
  private Set<UUID> scope(AgentRunSpec spec, Chat chat, Set<UUID> readableLibraryIds) {
    Set<UUID> scope = new HashSet<>(chatService.spaceLibraryScope(chat, readableLibraryIds));
    if (!spec.libraryIds().isEmpty()) {
      scope.retainAll(spec.libraryIds());
    }
    return scope;
  }

  private QueryResult runAndKeep(
      AgentTurn turn, AgentRunSpec spec, AgentRun run, ChatClient client) {
    String reminder = AgentPrompt.reminder(turn.spaceInstruction());
    List<ToolCallback> callbacks = callbacks(spec, run, reminder);
    ToolCallingManager toolCallingManager =
        DefaultToolCallingManager.builder()
            .observationRegistry(observationRegistry)
            .unlimitedTotalToolCalls()
            .unlimitedCallsPerTool()
            .resolutionFallbackEnabled(true)
            .toolCallbackResolver(
                name -> AgentToolCallback.unknown(reminder, objectMapper, metrics, clock))
            .build();
    String system =
        AgentPrompt.systemText(
            run.blockId(), spec.instruction(), turn.spaceInstruction(), notes(turn.notePoints()));
    Map<String, Object> toolContext = Map.of(AgentToolCallback.RUN_KEY, run);

    List<Message> history = new ArrayList<>(chatMemory.get(turn.conversationKey()));
    history.add(new UserMessage(turn.question()));

    int tokens = 0;
    boolean closing = false;
    ChatResponse response;
    while (true) {
      if (!closing && !clock.instant().isBefore(run.deadline())) {
        run.reachLimit(EndState.LIMIT_DURATION);
      }
      closing = closing || run.limitReached() != null;
      run.checkpoint();
      signal(turn, AnswerStreamListener::idle);
      // The closing call offers no tools: the model answers with what the steps collected.
      List<ToolCallback> offered = closing ? List.of() : callbacks;
      List<Message> asked = history;
      response = run.modelCall(() -> call(client, system, asked, offered, toolContext));
      tokens += ChatResponses.totalTokens(response);
      if (closing || callbacks.isEmpty() || !response.hasToolCalls()) {
        break;
      }
      ToolExecutionResult executed =
          toolCallingManager.executeToolCalls(
              new Prompt(
                  history,
                  ToolCallingChatOptions.builder()
                      .toolCallbacks(callbacks)
                      .toolContext(toolContext)
                      .build()),
              response);
      history = new ArrayList<>(executed.conversationHistory());
      if (run.question() != null) {
        // A question back to the person ends the run; the reply starts a new one.
        break;
      }
    }

    EndState endState =
        run.question() != null
            ? EndState.ASK_USER
            : run.limitReached() == null ? EndState.COMPLETED : run.limitReached();
    List<Document> passages = distinct(run.passages());
    String text = endState == EndState.ASK_USER ? run.question() : ChatResponses.text(response);
    if (text.isBlank() && endState != EndState.COMPLETED) {
      text = NO_ANSWER_AT_LIMIT;
    }
    // The answer is shown as provisional while its citations are validated and the turn is kept.
    String provisional = text;
    signal(turn, stream -> stream.runFinished(endState));
    signal(turn, stream -> stream.provisional(provisional, true));
    String answer = CitationMarkerRepair.repair(text, passages);
    List<CitationValidator.ValidatedCitation> citations =
        citationValidator.validate(citationParser.extractCitations(answer), passages, answer);
    metrics.recordUnverifiableCitations(
        spec.builtin(), (int) citations.stream().filter(c -> !c.valid()).count());
    List<ChatSource> sources =
        chatSourceAssembler.assemble(passages, citations, turn.metadataFilter());
    // A question back is complete as asked and states nothing that needs a citation.
    boolean asked = endState == EndState.ASK_USER;
    AnswerCompleteness completeness =
        asked ? AnswerCompleteness.COMPLETE : ChatResponses.completeness(response);
    List<String> searchQueries =
        run.steps().stream()
            .filter(step -> ToolNames.SEARCH_KNOWLEDGE.equals(step.tool()))
            .map(AgentRunRecord.Step::detail)
            .filter(detail -> detail != null)
            .toList();
    AnswerEvidence evidence =
        asked
            ? null
            : AnswerEvidenceAssessor.assess(
                citations,
                !run.searchScope().isEmpty(),
                !passages.isEmpty(),
                true,
                searchQueries,
                Map.of(),
                completeness);
    AgentRunRecord record =
        new AgentRunRecord(run.runId(), endState, run.steps(), spec.agentId(), spec.name());

    run.lastCheckpoint();
    signal(turn, AnswerStreamListener::beforeKeeping);
    List<ChatAttachment> generatedFiles = finishResources(run);
    Chat chat = turn.chat();
    String chatTitle =
        chatService.appendTurn(
            chat,
            turn.question(),
            turn.usedPrompt(),
            answer,
            sources,
            evidence,
            List.of(),
            generatedFiles.stream().map(ChatAttachment::getId).toList(),
            record);
    chatMemory.add(turn.conversationKey(), new UserMessage(turn.question()));
    ConversationWindowMessages.answer(answer)
        .ifPresent(message -> chatMemory.add(turn.conversationKey(), message));
    chatNoteExtractionService.condenseAsync(chat.getId(), chat.getSpaceId(), turn.question());
    queryMetrics.recordFinishReason(ChatResponses.finishReason(response));
    // Counts only: the sub-questions are the person's content and stay in the chat (ADR-0070).
    log.info(
        "Agent run {} ended {} after {} tool call(s) with {} passage(s)",
        run.runId(),
        endState,
        run.toolCalls(),
        passages.size());

    QueryOutcome outcome =
        new QueryOutcome(
            ChatResponses.model(response),
            tokens,
            System.currentTimeMillis() - turn.startTime(),
            false,
            false,
            false,
            false,
            chatSourceAssembler.searchedLibraries(run.searchScope()),
            completeness);
    return new QueryResult(
        answer,
        sources,
        outcome,
        chat.getId(),
        chatTitle,
        turn.notePoints(),
        evidence,
        generatedFiles,
        record);
  }

  /**
   * The agent's tools that are registered and switched on, in the registry's order, each as it
   * offers itself to this run.
   */
  private List<ToolCallback> callbacks(AgentRunSpec spec, AgentRun run, String reminder) {
    AgentToolSwitches switches = toolSwitches.getIfAvailable();
    Set<String> enabled = switches == null ? Set.of() : switches.enabledTools();
    List<ToolCallback> callbacks = new ArrayList<>();
    for (AgentToolInfo info : registry.tools()) {
      if (spec.toolNames().contains(info.name()) && enabled.contains(info.name())) {
        registry
            .find(info.name())
            .ifPresent(
                tool ->
                    callbacks.add(
                        AgentToolCallback.of(
                            tool, tool.offer(run), reminder, objectMapper, metrics, clock)));
      }
    }
    return callbacks;
  }

  private static ChatResponse call(
      ChatClient client,
      String system,
      List<Message> history,
      List<ToolCallback> callbacks,
      Map<String, Object> toolContext) {
    ChatClient.ChatClientRequestSpec request = client.prompt().system(system).messages(history);
    if (!callbacks.isEmpty()) {
      request =
          request
              .tools(callbacks.toArray())
              .toolContext(toolContext)
              .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
    }
    return request.call().chatResponse();
  }

  private static List<String> notes(List<ChatNotePoint> points) {
    return points == null ? List.of() : points.stream().map(ChatNotePoint::text).toList();
  }

  /**
   * The passages of all steps with each section once: two searches finding the same passage must
   * not count it twice in the source list.
   */
  private static List<Document> distinct(List<Document> passages) {
    Map<String, Document> byKey = new LinkedHashMap<>();
    for (Document passage : passages) {
      String key =
          ChunkGroupingKey.of(passage)
              + "#"
              + passage.getMetadata().getOrDefault("chunk_index", "0");
      byKey.putIfAbsent(key, passage);
    }
    return new ArrayList<>(byKey.values());
  }
}
