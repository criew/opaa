package io.opaa.searchadmin;

/**
 * The model roles the status display covers. Reranking is a role on the same level as chat and
 * embedding, not a procedure inside the search (docs/features/hybrid-retrieval.md, Arbeitspaket 4).
 */
public enum ModelRole {
  CHAT,
  EMBEDDING,
  RERANK
}
