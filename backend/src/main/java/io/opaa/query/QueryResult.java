package io.opaa.query;

import io.opaa.chat.ChatSource;
import java.util.List;
import java.util.UUID;

/**
 * {@link QueryService#query}'s return type: answer, sources, metadata and the chat it belongs to.
 * {@code QueryController} maps it to the generated response via {@code QueryResponseMapper}.
 */
public final class QueryResult {

  private final String answer;
  private final List<ChatSource> sources;
  private final QueryOutcome metadata;
  private final UUID chatId;
  private String chatTitle;

  public QueryResult(String answer, List<ChatSource> sources, QueryOutcome metadata, UUID chatId) {
    this.answer = answer;
    this.sources = sources;
    this.metadata = metadata;
    this.chatId = chatId;
  }

  public QueryResult chatTitle(String chatTitle) {
    this.chatTitle = chatTitle;
    return this;
  }

  public String getAnswer() {
    return answer;
  }

  public List<ChatSource> getSources() {
    return sources;
  }

  public QueryOutcome getMetadata() {
    return metadata;
  }

  public UUID getChatId() {
    return chatId;
  }

  public String getChatTitle() {
    return chatTitle;
  }
}
