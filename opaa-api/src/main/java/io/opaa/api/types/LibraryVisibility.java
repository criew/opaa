package io.opaa.api.types;

/**
 * How far a {@code KnowledgeLibrary} (backend module)'s access reaches, independent of individual
 * or group grants. See docs/features/spaces-and-assets.md#freigabestufen-und-auffindbarkeit.
 *
 * <ul>
 *   <li>{@link #PRIVATE} - no reach beyond the owner (and, for a group owner, its members).
 *   <li>{@link #SHARED} - reach follows whatever the owning group's membership is; distinguishing
 *       "team" from "Fachbereich" in the product vision is a property of which group owns the
 *       library, not of this enum.
 *   <li>{@link #ORGANIZATION} - readable by every user in the same organization.
 * </ul>
 *
 * <p>Full grant-based access (asset roles {@code USER}/{@code VIEWER}/{@code EDITOR}/{@code
 * MANAGER}/{@code OWNER}) is introduced in #202; this issue (#201) only carries the field and
 * enforces it for the coarse cases above.
 *
 * <p><b>Declaration order is a deliberate reach order</b> (PRIVATE &lt; SHARED &lt; ORGANIZATION),
 * relied on by {@link #exceeds}; a value inserted anywhere but the end would silently change what
 * that method answers.
 */
public enum LibraryVisibility {
  PRIVATE,
  SHARED,
  ORGANIZATION;

  /** Whether this reach goes further than {@code other} - the ordering {@link #PRIVATE} names. */
  public boolean exceeds(LibraryVisibility other) {
    return ordinal() > other.ordinal();
  }
}
