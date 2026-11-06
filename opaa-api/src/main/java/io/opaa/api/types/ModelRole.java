package io.opaa.api.types;

/**
 * The model roles the status display covers. Reranking is a role on the same level as chat and
 * embedding, not a procedure inside the search (ADR-0065, Entscheidung 6). Transcription serves
 * dictation in the chat input, text recognition reads scanned PDFs during indexing.
 */
public enum ModelRole {
  CHAT,
  EMBEDDING,
  RERANK,
  TRANSCRIPTION,
  TEXT_RECOGNITION
}
