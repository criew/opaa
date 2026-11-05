package io.opaa.query;

import io.opaa.chat.AnswerEvidence;
import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatSource;
import java.util.List;
import java.util.UUID;

/**
 * {@link QueryService#query}'s return type: answer, sources, metadata and the chat it belongs to.
 * {@code QueryController} maps it to the generated response via {@code QueryResponseMapper}.
 *
 * @param chatTitle the chat's title after this turn, {@code null} for a turn outside a chat.
 * @param noteItems the Gesprächsnotiz state that went into <em>this</em> answer, {@code null} for a
 *     turn outside a chat. Never the state of the condensation this turn triggers - that runs after
 *     this result is built, the same rule {@code chatTitle} follows.
 * @param evidence the answer's Belegstand, {@code null} when there is nothing to assess.
 * @param generatedFiles files a tool of the agent loop produced, bound to the answer's message;
 *     empty on every other path.
 */
public record QueryResult(
    String answer,
    List<ChatSource> sources,
    QueryOutcome metadata,
    UUID chatId,
    String chatTitle,
    List<ChatNotePoint> noteItems,
    AnswerEvidence evidence,
    List<ChatAttachment> generatedFiles) {

  /** A result without generated files - every path but the agent loop. */
  public QueryResult(
      String answer,
      List<ChatSource> sources,
      QueryOutcome metadata,
      UUID chatId,
      String chatTitle,
      List<ChatNotePoint> noteItems,
      AnswerEvidence evidence) {
    this(answer, sources, metadata, chatId, chatTitle, noteItems, evidence, List.of());
  }

  /** A result without a Belegstand - a path that does not assess one. */
  public QueryResult(
      String answer,
      List<ChatSource> sources,
      QueryOutcome metadata,
      UUID chatId,
      String chatTitle,
      List<ChatNotePoint> noteItems) {
    this(answer, sources, metadata, chatId, chatTitle, noteItems, null);
  }
}
