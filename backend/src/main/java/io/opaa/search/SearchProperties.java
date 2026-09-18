package io.opaa.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parameters of the reading path (#1720). Ebene-1 values: overridable per environment variable,
 * absent from every administration surface.
 *
 * @param defaultMaxHits hits a request without its own {@code maxHits} receives. Never more than
 *     what the retrieval pipeline selected - {@code opaa.query.top-k} remains the real ceiling.
 * @param maxHits the server-side cap on a request's own {@code maxHits}. A larger value is reduced
 *     to it rather than rejected, so a foreign tool asking for a round number still gets an answer.
 * @param excerptMaxCharacters the length a hit's excerpt is cut to. Only the excerpt: the whole
 *     passage is one fetch away, and an unbounded excerpt would make the hit list itself the
 *     retrieval path.
 * @param fetchMaxCharacters the cap on {@code full=true}, the one place where a single call can
 *     return a whole document. Deliberately finite even though the largest documents fit below it:
 *     the cap is the difference between "I asked for a document" and "I asked for the holdings".
 * @param contextPassages how many passages on each side of the hit the default fetch adds.
 */
@ConfigurationProperties(prefix = "opaa.search")
public record SearchProperties(
    @DefaultValue("10") int defaultMaxHits,
    @DefaultValue("50") int maxHits,
    @DefaultValue("1500") int excerptMaxCharacters,
    @DefaultValue("200000") int fetchMaxCharacters,
    @DefaultValue("1") int contextPassages) {

  public SearchProperties {
    if (defaultMaxHits <= 0) {
      throw new IllegalArgumentException("defaultMaxHits must be positive, got " + defaultMaxHits);
    }
    if (maxHits < defaultMaxHits) {
      throw new IllegalArgumentException(
          "maxHits must be at least defaultMaxHits, got maxHits="
              + maxHits
              + " defaultMaxHits="
              + defaultMaxHits);
    }
    if (excerptMaxCharacters <= 0) {
      throw new IllegalArgumentException(
          "excerptMaxCharacters must be positive, got " + excerptMaxCharacters);
    }
    if (fetchMaxCharacters < excerptMaxCharacters) {
      throw new IllegalArgumentException(
          "fetchMaxCharacters must be at least excerptMaxCharacters, got " + fetchMaxCharacters);
    }
    if (contextPassages < 0 || contextPassages > 10) {
      throw new IllegalArgumentException(
          "contextPassages must be between 0 and 10, got " + contextPassages);
    }
  }

  /** {@code requested} narrowed to the cap, or the default when none was requested. */
  public int effectiveMaxHits(Integer requested) {
    if (requested == null || requested <= 0) {
      return defaultMaxHits;
    }
    return Math.min(requested, maxHits);
  }
}
