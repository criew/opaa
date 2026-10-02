package io.opaa.chat;

import java.time.Instant;

/**
 * A chat as one person sees it in their list: the chat plus that person's own marks.
 *
 * @param pinnedAt when the person pinned the chat, or {@code null} if they have not
 * @param archivedAt when the person archived the chat, or {@code null} if it is active for them
 * @param deletionDueAt when the automatic chat cleanup of the space deletes the archived chat, or
 *     {@code null} if it is active or the cleanup is off
 */
public record ChatListEntry(
    Chat chat, Instant pinnedAt, Instant archivedAt, Instant deletionDueAt) {

  public ChatListEntry(Chat chat, Instant pinnedAt, Instant archivedAt) {
    this(chat, pinnedAt, archivedAt, null);
  }
}
