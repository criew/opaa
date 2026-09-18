package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The best spot of one chat for a chat search term. A match in a message names that message; a chat
 * that matches only by its title has no {@code messageId}, {@code role} or {@code
 * messageCreatedAt}, and its excerpt is the title.
 *
 * @param archivedAt when the searching person archived the chat, or {@code null}
 * @param excerpt plain text around the match, never markup
 * @param highlights the matched words within {@code excerpt}, ascending and non-overlapping
 */
public record ChatSearchMatch(
    UUID chatId,
    String title,
    Instant archivedAt,
    UUID messageId,
    ChatRole role,
    Instant messageCreatedAt,
    String excerpt,
    List<Highlight> highlights) {

  public ChatSearchMatch {
    highlights = List.copyOf(highlights);
  }

  /**
   * A range of the excerpt in UTF-16 code units: {@code start} inclusive, {@code end} exclusive.
   */
  public record Highlight(int start, int end) {}
}
