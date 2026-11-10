package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;
import io.opaa.chat.AgentRunRecord.EndState;
import io.opaa.chat.AgentRunStamp;
import io.opaa.metadata.MetadataFilter;
import io.opaa.query.answer.DocumentBlock;
import io.opaa.query.runlog.AgentStepLogEntry;
import io.opaa.retrieval.context.ChunkPosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;

/**
 * The state of one run (ADR-0070, Entscheidung 1): who asks, in which scope, under which limits,
 * and what the steps collected. One instance per request, handed to the tools through the {@code
 * ToolContext}; it never outlives the request and is never shared. Scope and filter are fixed at
 * the start and no tool argument widens them.
 */
public final class AgentRun {

  private final UUID runId = UUID.randomUUID();
  private final UUID organizationId;
  private final UUID userId;
  private final UUID chatId;
  private final UUID spaceId;
  private final Set<UUID> searchScope;
  private final MetadataFilter metadataFilter;
  private final AgentLimits limits;
  private final Instant deadline;
  private final String blockId = DocumentBlock.newId();

  private final List<Document> passages = new ArrayList<>();
  private final List<AgentRunRecord.Step> steps = new ArrayList<>();
  private final List<AgentStepLogEntry> trace = new ArrayList<>();
  private final Map<String, Integer> callsPerTool = new HashMap<>();
  private final Map<Class<?>, RunResource> resources = new LinkedHashMap<>();
  private int toolCalls;
  private long collectedChars;
  private EndState limitReached;
  private String question;
  private AgentRunStamp stamp;
  private String agentName;
  private volatile AgentRunEvents events = AgentRunEvents.NONE;

  AgentRun(
      UUID organizationId,
      UUID userId,
      UUID chatId,
      UUID spaceId,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter,
      AgentLimits limits,
      Instant startedAt) {
    this.organizationId = organizationId;
    this.userId = userId;
    this.chatId = chatId;
    this.spaceId = spaceId;
    this.searchScope = Set.copyOf(searchScope);
    this.metadataFilter = metadataFilter == null ? MetadataFilter.NONE : metadataFilter;
    this.limits = limits;
    this.deadline = startedAt.plus(limits.maxDuration());
  }

  public UUID runId() {
    return runId;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public UUID userId() {
    return userId;
  }

  public UUID chatId() {
    return chatId;
  }

  public UUID spaceId() {
    return spaceId;
  }

  /**
   * The libraries the run may read: those the agent names, the caller may read and the chat's space
   * has associated. A tool narrows within it, never beyond.
   */
  public Set<UUID> searchScope() {
    return searchScope;
  }

  /** The chat's metadata filter; a tool argument may only add conditions to it. */
  public MetadataFilter metadataFilter() {
    return metadataFilter;
  }

  /** The id that fences every tool result of this run and that the system text names. */
  public String blockId() {
    return blockId;
  }

  /** Tells {@code listener} the run's steps and its limit from now on. */
  void listen(AgentRunEvents listener) {
    this.events = listener == null ? AgentRunEvents.NONE : listener;
  }

  /** Ends the run here when it must end, before a model call or a tool call. */
  void checkpoint() {
    events.checkpoint();
  }

  /** The {@link #checkpoint} before the run is kept; see {@link AgentRunEvents#lastCheckpoint}. */
  void lastCheckpoint() {
    events.lastCheckpoint();
  }

  /** Runs the model call {@code call} as the listener allows it. */
  <T> T modelCall(Supplier<T> call) {
    return events.modelCall(call);
  }

  /**
   * Announces the tool call about to run as the next step, after the {@link #checkpoint}; a
   * listener that throws keeps the call from running.
   */
  void startStep(String toolName, String detail) {
    events.checkpoint();
    int index;
    synchronized (this) {
      index = steps.size();
    }
    events.stepStarted(index, toolName, detail);
  }

  AgentLimits limits() {
    return limits;
  }

  Instant deadline() {
    return deadline;
  }

  /**
   * The limit a call of {@code toolName} at {@code now} would cross, or {@code null}: the run's
   * wall clock, its total of calls, the calls of {@code search_knowledge} and the collected volume,
   * in that order. Checked before the call, so a refused call costs nothing.
   */
  synchronized EndState limitBefore(String toolName, Instant now) {
    if (!now.isBefore(deadline)) {
      return EndState.LIMIT_DURATION;
    }
    if (toolCalls >= limits.maxToolCalls()) {
      return EndState.LIMIT_CALLS;
    }
    if (ToolNames.SEARCH_KNOWLEDGE.equals(toolName)
        && callsPerTool.getOrDefault(toolName, 0) >= limits.maxSearchCalls()) {
      return EndState.LIMIT_CALLS;
    }
    if (collectedChars >= limits.maxCollectedChars()) {
      return EndState.LIMIT_VOLUME;
    }
    return null;
  }

  /** Counts a call that ran, with the characters it handed to the model and its passages. */
  void recordCall(String toolName, ToolCallOutcome outcome) {
    AgentRunRecord.Step step =
        new AgentRunRecord.Step(
            toolName, outcome.detail(), outcome.hits(), outcome.result().stepStatus());
    int index;
    synchronized (this) {
      toolCalls++;
      callsPerTool.merge(toolName, 1, Integer::sum);
      collectedChars += outcome.modelText() == null ? 0 : outcome.modelText().length();
      passages.addAll(outcome.passages());
      index = steps.size();
      steps.add(step);
    }
    events.stepFinished(index, step);
  }

  /**
   * Notes a step for the step log (ADR-0070, Bestand b): technical facts only, never the step's
   * detail, which is the person's content and stays in the chat.
   */
  synchronized void traceStep(
      String toolName, String status, Integer hits, long durationMs, String abortReason) {
    trace.add(
        new AgentStepLogEntry(trace.size(), null, toolName, status, hits, durationMs, abortReason));
  }

  /** The steps for the step log, in order. */
  synchronized List<AgentStepLogEntry> trace() {
    return List.copyOf(trace);
  }

  /** Notes a step that did not run, e.g. a call of an unknown tool. */
  void recordStep(AgentRunRecord.Step step) {
    int index;
    synchronized (this) {
      index = steps.size();
      steps.add(step);
    }
    events.stepFinished(index, step);
  }

  /**
   * Marks the run as ended at {@code limit}; the first limit reached is the one that stays and the
   * only one announced.
   */
  void reachLimit(EndState limit) {
    synchronized (this) {
      if (limitReached != null) {
        return;
      }
      limitReached = limit;
    }
    events.limitReached(limit);
  }

  /**
   * Ends the run with a question back to the person (ADR-0070, Entscheidung 1); the first question
   * stays. The loop makes no further model call, and the person's reply starts a new run.
   */
  synchronized void askUser(String text) {
    if (question == null) {
      question = text;
    }
  }

  /** The question back to the person, or {@code null} while the run has none. */
  synchronized String question() {
    return question;
  }

  /** The characters the run may still collect before it reaches its volume limit. */
  synchronized long remainingChars() {
    return Math.max(0, limits.maxCollectedChars() - collectedChars);
  }

  /**
   * Whether a step of this run handed the model a passage of {@code documentId}: only such a
   * document - found in the run's scope under its filter - may be read further.
   */
  synchronized boolean found(String documentId) {
    return passages.stream()
        .anyMatch(
            passage ->
                ChunkPosition.of(passage)
                    .map(position -> position.documentId().equals(documentId))
                    .orElse(false));
  }

  /**
   * The run's resource of {@code type}, opened with {@code open} on first use and kept until the
   * run ends; a later call of the same tool gets the same one.
   */
  synchronized <T extends RunResource> T resource(Class<T> type, Supplier<T> open) {
    RunResource existing = resources.get(type);
    if (existing != null) {
      return type.cast(existing);
    }
    T opened = open.get();
    resources.put(type, opened);
    return opened;
  }

  /** Every resource the run's tools opened, in the order they were opened. */
  synchronized List<RunResource> resources() {
    return List.copyOf(resources.values());
  }

  synchronized EndState limitReached() {
    return limitReached;
  }

  /** Every passage any step handed to the model, in order, possibly repeated. */
  synchronized List<Document> passages() {
    return List.copyOf(passages);
  }

  synchronized List<AgentRunRecord.Step> steps() {
    return List.copyOf(steps);
  }

  synchronized int toolCalls() {
    return toolCalls;
  }

  /**
   * Sets the stamp the loop builds at the start - revision, scope by name and model - and the
   * agent's name it goes with.
   */
  synchronized void stampWith(String agentName, AgentRunStamp base) {
    this.agentName = agentName;
    this.stamp = base;
  }

  /** The agent's name the run's stamp goes with, {@code null} when none is known. */
  synchronized String agentName() {
    return agentName;
  }

  /**
   * Notes a template a tool filled, with the revision it filled, and returns the run's stamp with
   * it; the stamp's {@link AgentRunStamp#documentText} goes into the file. {@code null} while the
   * run has no stamp.
   */
  synchronized AgentRunStamp recordTemplate(AgentRunStamp.TemplateRef template) {
    if (stamp != null) {
      stamp = stamp.withTemplate(template);
    }
    return stamp;
  }

  /** The run's stamp as it stands, {@code null} when the loop set none. */
  synchronized AgentRunStamp stamp() {
    return stamp;
  }
}
