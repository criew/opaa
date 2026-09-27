package io.opaa.indexing.source.confluence;

import java.util.Objects;

/**
 * One selected space of a {@code CONFLUENCE} library (ADR-0023, Entscheidung 1). {@code spaceKey}
 * is the stable Confluence key a run lists, {@code spaceName} the display name at selection time so
 * the UI can show the selection without asking Confluence again. Equality is by key alone: two
 * selections of the same key are the same selection, whatever name the space carried when it was
 * picked.
 */
public final class ConfluenceSpaceSelection {

  private final String spaceKey;
  private final String spaceName;

  public ConfluenceSpaceSelection(String spaceKey, String spaceName) {
    this.spaceKey = Objects.requireNonNull(spaceKey, "spaceKey");
    this.spaceName = spaceName;
  }

  public String getSpaceKey() {
    return spaceKey;
  }

  public String getSpaceName() {
    return spaceName;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ConfluenceSpaceSelection other && spaceKey.equals(other.spaceKey);
  }

  @Override
  public int hashCode() {
    return spaceKey.hashCode();
  }

  @Override
  public String toString() {
    return "ConfluenceSpaceSelection[" + spaceKey + "]";
  }
}
