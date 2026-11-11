package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatMessageResponse} - one persisted {@link
 * ChatMessage}, with its JSON-serialized {@code sources} column already parsed back into {@link
 * ChatSource}s. Built by {@link ChatService} for {@link ChatConversation#getMessages()}; immutable,
 * since a turn is read once it is persisted and never mutated in place afterwards (unlike {@link
 * ChatSource}, which {@code ChatSourceAssembler} still mutates while ranking/merging before a turn
 * is ever built).
 */
public final class ChatTurn {

  private final UUID id;
  private final UUID chatId;
  private final ChatRole role;
  private final String content;
  private final List<ChatSource> sources;
  private final UsedPrompt usedPrompt;
  private final Instant createdAt;
  private final AnswerEvidence evidence;
  private final AgentRunRecord agentRun;
  private final List<ChatAttachment> attachments;
  private final Integer currentAgentRevision;

  public ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      Instant createdAt) {
    this(id, chatId, role, content, sources, null, createdAt);
  }

  public ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      UsedPrompt usedPrompt,
      Instant createdAt) {
    this(id, chatId, role, content, sources, usedPrompt, createdAt, null);
  }

  public ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      UsedPrompt usedPrompt,
      Instant createdAt,
      AnswerEvidence evidence) {
    this(id, chatId, role, content, sources, usedPrompt, createdAt, evidence, null);
  }

  public ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      UsedPrompt usedPrompt,
      Instant createdAt,
      AnswerEvidence evidence,
      AgentRunRecord agentRun) {
    this(
        id,
        chatId,
        role,
        content,
        sources,
        usedPrompt,
        createdAt,
        evidence,
        agentRun,
        List.of(),
        null);
  }

  private ChatTurn(
      UUID id,
      UUID chatId,
      ChatRole role,
      String content,
      List<ChatSource> sources,
      UsedPrompt usedPrompt,
      Instant createdAt,
      AnswerEvidence evidence,
      AgentRunRecord agentRun,
      List<ChatAttachment> attachments,
      Integer currentAgentRevision) {
    this.currentAgentRevision = currentAgentRevision;
    this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
    this.evidence = evidence;
    this.agentRun = agentRun;
    this.id = id;
    this.chatId = chatId;
    this.role = role;
    this.content = content;
    this.sources = sources;
    this.usedPrompt = usedPrompt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getChatId() {
    return chatId;
  }

  public ChatRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public List<ChatSource> getSources() {
    return sources;
  }

  /** The prompt snapshot of a question, or {@code null}. */
  public UsedPrompt getUsedPrompt() {
    return usedPrompt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** The Belegstand of an answer, or {@code null}. */
  public AnswerEvidence getEvidence() {
    return evidence;
  }

  /** The agent run that gave this answer, or {@code null}. */
  public AgentRunRecord getAgentRun() {
    return agentRun;
  }

  /** The attachments sent with this question that still exist; empty otherwise. */
  public List<ChatAttachment> getAttachments() {
    return attachments;
  }

  /** This turn with {@code attachments} ({@code null}: none). */
  public ChatTurn withAttachments(List<ChatAttachment> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      return this;
    }
    return new ChatTurn(
        id,
        chatId,
        role,
        content,
        sources,
        usedPrompt,
        createdAt,
        evidence,
        agentRun,
        attachments,
        currentAgentRevision);
  }

  /**
   * The revision the stamped agent has now, {@code null} when the turn has no stamp or the person
   * may no longer read the agent. Not persisted: looked up whenever the chat is loaded.
   */
  public Integer getCurrentAgentRevision() {
    return currentAgentRevision;
  }

  /** This turn with the stamped agent's current revision ({@code null}: unknown). */
  public ChatTurn withCurrentAgentRevision(Integer revision) {
    if (revision == null) {
      return this;
    }
    return new ChatTurn(
        id,
        chatId,
        role,
        content,
        sources,
        usedPrompt,
        createdAt,
        evidence,
        agentRun,
        attachments,
        revision);
  }
}
