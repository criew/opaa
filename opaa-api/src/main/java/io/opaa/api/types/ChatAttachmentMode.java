package io.opaa.api.types;

/**
 * How a ready chat attachment goes into an answer: as a whole, or as the passages that best fit the
 * question once the text exceeds the full-text threshold. Derived, never persisted.
 */
public enum ChatAttachmentMode {
  FULL_TEXT,
  EXCERPTS
}
