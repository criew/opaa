package io.opaa.retrieval.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How far the answer prompt widens each selected chunk by its neighbours in the same document
 * (docs/handbuch/suche.md Abschnitt 10.3). An Ebene-1 value like {@code QueryProperties}.
 *
 * @param radius how many chunks on each side of a selected chunk are loaded, {@code chunk_index ±
 *     radius}. Default {@code 1}; {@code 0} switches the expansion off. At most 3.
 * @param maxAddedCharacters upper bound on the characters all loaded neighbours of one answer may
 *     add to the prompt. Neighbours are added nearest first and in the rank order of their selected
 *     chunk; one that no longer fits is left out. Default 40000.
 */
@ConfigurationProperties(prefix = "opaa.query.neighbor-chunks")
public record NeighborChunkProperties(
    @DefaultValue("1") int radius, @DefaultValue("40000") int maxAddedCharacters) {

  public NeighborChunkProperties {
    if (radius < 0 || radius > 3) {
      throw new IllegalArgumentException("radius must be between 0 and 3, got " + radius);
    }
    if (maxAddedCharacters < 0 || maxAddedCharacters > 1_000_000) {
      throw new IllegalArgumentException(
          "maxAddedCharacters must be between 0 and 1000000, got " + maxAddedCharacters);
    }
  }

  /** Whether the expansion runs at all. */
  public boolean enabled() {
    return radius > 0 && maxAddedCharacters > 0;
  }
}
