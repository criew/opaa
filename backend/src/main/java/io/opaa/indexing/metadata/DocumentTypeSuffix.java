package io.opaa.indexing.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One seeded Kompositum ending of a Dokumentart (migration 020): a token that <em>ends</em> on
 * {@code suffix} and carries at least {@code minPrefixLength} further characters in front of it
 * denotes this Dokumentart - {@code verwaltungsgebuehrensatzung} is a Satzung, {@code anordnung} is
 * not an Ordnung. Deterministic string comparison, no distance measure; the seed is where an
 * installation tunes both the endings and the minimum, not the code.
 */
@Embeddable
public class DocumentTypeSuffix {

  @Column(name = "suffix", nullable = false, length = 100)
  private String suffix;

  @Column(name = "min_prefix_length", nullable = false)
  private int minPrefixLength;

  protected DocumentTypeSuffix() {}

  public DocumentTypeSuffix(String suffix, int minPrefixLength) {
    this.suffix = suffix;
    this.minPrefixLength = minPrefixLength;
  }

  public String getSuffix() {
    return suffix;
  }

  public int getMinPrefixLength() {
    return minPrefixLength;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DocumentTypeSuffix that
        && minPrefixLength == that.minPrefixLength
        && suffix.equals(that.suffix);
  }

  @Override
  public int hashCode() {
    return suffix.hashCode() * 31 + minPrefixLength;
  }
}
