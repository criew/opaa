package io.opaa.query.retrieval;

import java.util.Locale;

/**
 * Every candidate list label a retrieval stage can put on a {@link CandidateList}, closed over this
 * enum for the same reason as {@link RetrievalNote} - the label stays a free technical field, but
 * the set of labels the backend can produce is enumerable so {@code RetrievalNoteTest} can hold it
 * against the German translation inventory in {@code
 * frontend/src/utils/retrievalProtocolText.test.ts}.
 */
public enum RetrievalListLabel {
  VECTOR_SEARCH("vector search · sub-query %d"),
  FULL_TEXT_SEARCH("full-text search · sub-query %d"),
  FUSED("fused (RRF)");

  /** The label of the single list every stage from {@code RANK_FUSION} on works with. */
  public static final String FUSED_LIST_LABEL = FUSED.format();

  private final String template;

  RetrievalListLabel(String template) {
    this.template = template;
  }

  /** The raw {@link String#format} template, for the test that pins the set of labels down. */
  String template() {
    return template;
  }

  /** The label text with {@code args} filled in, in {@link Locale#ROOT}. */
  public String format(Object... args) {
    return String.format(Locale.ROOT, template, args);
  }
}
