package io.opaa.chat;

import io.opaa.api.types.ChatNoteItemKind;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted Gesprächsnotiz point as it leaves the chat package (#860 Teil 4): the read view of
 * {@link ChatNoteItem}, carried by {@link ChatConversation} and {@code QueryResult} and mapped to
 * the generated {@code ChatNoteItem} response in {@code io.opaa.api}.
 */
public record ChatNotePoint(UUID id, String text, ChatNoteItemKind kind, Instant createdAt) {}
