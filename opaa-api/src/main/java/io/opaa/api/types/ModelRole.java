package io.opaa.api.types;

/**
 * The model roles the status display covers. Reranking is a role on the same level as chat and
 * embedding, not a procedure inside the search (docs/features/hybrid-retrieval.md, Arbeitspaket 4).
 * Transcription serves dictation in the chat input.
 */
public enum ModelRole {
  CHAT,
  EMBEDDING,
  RERANK,
  TRANSCRIPTION
}
