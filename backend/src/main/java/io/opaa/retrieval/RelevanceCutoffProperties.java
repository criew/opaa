package io.opaa.retrieval;

import io.opaa.api.types.RetrievalStageName;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How {@link RetrievalStageName#RELEVANCE_CUTOFF} sizes the selection ({@code
 * opaa.query.relevance-cutoff.*}, docs/features/retrieval-algorithm.md, step 5c). Ebene-1 values.
 * {@link QueryProperties#topK} stays the upper bound in every mode: the cutoff only removes.
 *
 * @param mode {@link Mode#FIXED} keeps the selection as fusion or reranking left it; {@link
 *     Mode#RELATIVE} and {@link Mode#KNEE} cut it by score. Default {@link Mode#FIXED}.
 * @param minChunks the selection never shrinks below this many chunks (or below what it has).
 *     Default 10, between 1 and 100.
 * @param relativeThreshold {@link Mode#RELATIVE}: a chunk stays if its score reaches this share of
 *     the best score of the selection. Default 0.6, above 0 and at most 1.
 * @param kneeDrop {@link Mode#KNEE}: the selection ends before the first chunk whose score lies
 *     this share of the best score below its predecessor's. Default 0.25, above 0 and at most 1.
 */
@ConfigurationProperties(prefix = "opaa.query.relevance-cutoff")
public record RelevanceCutoffProperties(
    @DefaultValue("FIXED") Mode mode,
    @DefaultValue("10") int minChunks,
    @DefaultValue("0.6") double relativeThreshold,
    @DefaultValue("0.25") double kneeDrop) {

  /** The shipped configuration: no cutoff. */
  public static final RelevanceCutoffProperties DEFAULT =
      new RelevanceCutoffProperties(Mode.FIXED, 10, 0.6, 0.25);

  /** How the selection is sized. */
  public enum Mode {
    /** Up to {@code top-k}, whatever the scores. */
    FIXED,
    /** Down to the chunks scoring at least {@code relativeThreshold} of the best. */
    RELATIVE,
    /** Down to the chunks before the first drop of at least {@code kneeDrop} of the best. */
    KNEE
  }

  public RelevanceCutoffProperties {
    if (mode == null) {
      mode = Mode.FIXED;
    }
    if (minChunks < 1 || minChunks > 100) {
      throw new IllegalArgumentException(
          "relevance-cutoff.minChunks must be between 1 and 100, got " + minChunks);
    }
    if (relativeThreshold <= 0.0 || relativeThreshold > 1.0) {
      throw new IllegalArgumentException(
          "relevance-cutoff.relativeThreshold must be above 0 and at most 1, got "
              + relativeThreshold);
    }
    if (kneeDrop <= 0.0 || kneeDrop > 1.0) {
      throw new IllegalArgumentException(
          "relevance-cutoff.kneeDrop must be above 0 and at most 1, got " + kneeDrop);
    }
  }
}
