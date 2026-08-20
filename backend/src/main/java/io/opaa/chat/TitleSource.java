package io.opaa.chat;

/**
 * Where a {@link Chat}'s current {@link Chat#getTitle() title} came from (#557). Purely internal
 * bookkeeping - not exposed via the API - that lets {@link ChatTitleGenerationService}'s
 * asynchronous, LLM-derived title generation and {@link
 * ChatRepository#deriveTitleFromFirstQuestionIfAbsent} both operate on the same chat without ever
 * overwriting a title the user chose themselves.
 */
public enum TitleSource {

  /**
   * The current title is system-derived: either the mechanical prefix-of-the-first-question
   * fallback ({@link ChatRepository#deriveTitleFromFirstQuestionIfAbsent}) or an LLM-generated one
   * ({@link ChatRepository#applyGeneratedTitleIfGenerated}) - both may still be replaced by a
   * later, more authoritative title of either kind, but never by a user-initiated change
   * downgrading it back from {@link #CUSTOM}.
   */
  GENERATED,

  /**
   * The user set this title themselves - at chat creation ({@code ChatCreateRequest#getTitle()}) or
   * via a later {@code PATCH} ({@code ChatUpdateRequest#getTitle()}), including an explicit blank
   * string. Permanent for the life of the chat: neither the prefix fallback nor the LLM-derived
   * title generation ever overwrites a {@code CUSTOM} title, no matter when either would otherwise
   * run (#557 acceptance criterion).
   */
  CUSTOM
}
