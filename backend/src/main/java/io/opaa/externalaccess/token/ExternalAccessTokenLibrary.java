package io.opaa.externalaccess.token;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a token's library selection. The selection is immutable, so the library id is set
 * once and never changes; {@code extinguishedAt} is the single fact that may still be written to an
 * entry.
 *
 * <p><b>Why an entry can be extinguished.</b> A library whose release is suspended or has run out
 * stops acting - and it must not come back to life on its own if the library is released again
 * later (ADR-0035, Entscheidung 4: a token whose scope grows without the person doing anything is
 * wrong from the same direction as a selection "all, including future ones"). The first call that
 * finds the release gone therefore marks the entry, and no later release undoes that mark. The
 * entry stays in the row so the person still sees what they once selected.
 *
 * <p>Equality is the library id alone: the mark is mutable, and a mutable field in {@code equals}
 * would break the element collection's set semantics the moment it is set.
 */
@Embeddable
public class ExternalAccessTokenLibrary {

  @Column(name = "library_id", nullable = false, updatable = false)
  private UUID libraryId;

  @Column(name = "extinguished_at")
  private Instant extinguishedAt;

  protected ExternalAccessTokenLibrary() {}

  ExternalAccessTokenLibrary(UUID libraryId) {
    this.libraryId = Objects.requireNonNull(libraryId, "libraryId");
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public Instant getExtinguishedAt() {
    return extinguishedAt;
  }

  /** Whether the entry still takes part in the effective view. */
  public boolean isLive() {
    return extinguishedAt == null;
  }

  /** Marks the entry as gone for good; a second call keeps the first moment. */
  void extinguish(Instant now) {
    if (extinguishedAt == null) {
      this.extinguishedAt = Objects.requireNonNull(now, "now");
    }
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || (o instanceof ExternalAccessTokenLibrary other && libraryId.equals(other.libraryId));
  }

  @Override
  public int hashCode() {
    return libraryId.hashCode();
  }
}
