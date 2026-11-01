package io.opaa.chat;

import io.opaa.api.types.ChatAttachmentMode;
import java.time.Instant;

/**
 * A chat attachment as its author sees it: with the title of its chat, how it would go into an
 * answer ({@code null} unless ready) and, while no question names it yet, when it is deleted.
 */
public record ChatAttachmentView(
    ChatAttachment attachment, String chatTitle, ChatAttachmentMode mode, Instant deletionDueAt) {}
