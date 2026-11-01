package io.opaa.api.types;

/**
 * The reading state of a chat attachment, persisted in {@code chat_attachments.status}. Only a
 * {@link #READY} attachment goes into an answer.
 */
public enum ChatAttachmentStatus {
  PROCESSING,
  READY,
  FAILED
}
