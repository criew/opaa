package io.opaa.retrieval.context;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How far the answer prompt widens each selected chunk by its neighbours in the same document
 * (docs/handbuch/referenz/konfiguration.md). An Ebene-1 value like {@code QueryProperties}.
 *
 * @param radius how many chunks on each side of a selected chunk are loaded, {@code chunk_index ±
 *     radius}. Default {@code 1}; {@code 0} switches the expansion off. At most 3.
 * @param maxAddedCharacters upper bound on the characters all loaded neighbours of one answer may
 *     add to the prompt. Every selected chunk gets one neighbour first, the rest goes nearest first
 *     and in rank order; one that no longer fits is left out. Default 40000.
 * @param wholeTableMaxCharacters a selected chunk of a table no larger than this many characters
 *     brings the whole table, from the same budget, so rows keep their header and their
 *     neighbouring rows. Default 12000; {@code 0} loads no table whole. Runs only while the
 *     expansion itself is enabled.
 */
@PerInstance("search tuning of this process")
@NoDataImpact("search quality")
@ConfigurationProperties(prefix = "opaa.query.neighbor-chunks")
public record NeighborChunkProperties(
    @DefaultValue("1") int radius,
    @DefaultValue("40000") int maxAddedCharacters,
    @DefaultValue("12000") int wholeTableMaxCharacters) {

  public NeighborChunkProperties {
    if (radius < 0 || radius > 3) {
      throw new IllegalArgumentException("radius must be between 0 and 3, got " + radius);
    }
    if (maxAddedCharacters < 0 || maxAddedCharacters > 1_000_000) {
      throw new IllegalArgumentException(
          "maxAddedCharacters must be between 0 and 1000000, got " + maxAddedCharacters);
    }
    if (wholeTableMaxCharacters < 0 || wholeTableMaxCharacters > 1_000_000) {
      throw new IllegalArgumentException(
          "wholeTableMaxCharacters must be between 0 and 1000000, got " + wholeTableMaxCharacters);
    }
  }

  /** Whether the expansion runs at all. */
  public boolean enabled() {
    return radius > 0 && maxAddedCharacters > 0;
  }
}
