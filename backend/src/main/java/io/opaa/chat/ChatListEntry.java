package io.opaa.chat;

import java.time.Instant;

/**
 * A chat as one person sees it in their list: the chat plus that person's own marks.
 *
 * @param pinnedAt when the person pinned the chat, or {@code null} if they have not
 */
public record ChatListEntry(Chat chat, Instant pinnedAt) {}
