package io.opaa.chat;

import io.opaa.api.types.ChatNoteItemKind;

/**
 * One point a condensation produced, before it is known whether the note accepts it: already
 * parsed, trimmed and shortened to {@link ChatNoteExtraction#MAX_TEXT_LENGTH}, but without a
 * position, without an id, and not yet deduplicated against the chat's current note.
 */
public record ChatNoteCandidate(String text, ChatNoteItemKind kind) {}
