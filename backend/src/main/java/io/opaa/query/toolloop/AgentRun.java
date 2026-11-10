package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;
import io.opaa.chat.AgentRunRecord.EndState;
import io.opaa.metadata.MetadataFilter;
import io.opaa.query.answer.DocumentBlock;
import io.opaa.retrieval.context.ChunkPosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
  private final Map<String, Integer> callsPerTool = new HashMap<>();
  private int toolCalls;
  private long collectedChars;
  private EndState limitReached;
  private String question;

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
  synchronized void recordCall(String toolName, ToolCallOutcome outcome) {
    toolCalls++;
    callsPerTool.merge(toolName, 1, Integer::sum);
    collectedChars += outcome.modelText() == null ? 0 : outcome.modelText().length();
    passages.addAll(outcome.passages());
    steps.add(
        new AgentRunRecord.Step(
            toolName, outcome.detail(), outcome.hits(), outcome.result().stepStatus()));
  }

  /** Notes a step that did not run, e.g. a call of an unknown tool. */
  synchronized void recordStep(AgentRunRecord.Step step) {
    steps.add(step);
  }

  /** Marks the run as ended at {@code limit}; the first limit reached is the one that stays. */
  synchronized void reachLimit(EndState limit) {
    if (limitReached == null) {
      limitReached = limit;
    }
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
}
