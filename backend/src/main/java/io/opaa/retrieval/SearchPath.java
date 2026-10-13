package io.opaa.retrieval;

/**
 * Which search produced a {@link CandidateList}. The fusion needs it to read a candidate's score: a
 * cosine similarity on the vector path, a {@code ts_rank} on the lexical one.
 */
public enum SearchPath {
  VECTOR,
  FULL_TEXT
}
