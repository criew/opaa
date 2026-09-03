package io.opaa.library;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * One selected space of a {@code CONFLUENCE} library (ADR-0023, Entscheidung 1) - a row of {@code
 * knowledge_library_confluence_spaces}. {@code spaceKey} is the stable Confluence key a run lists,
 * {@code spaceName} the display name at selection time so the UI can show the selection without
 * asking Confluence again. Equality is by key alone: two selections of the same key are the same
 * selection, whatever name the space carried when it was picked.
 */
@Embeddable
public class ConfluenceSpaceSelection {

  @Column(name = "space_key", nullable = false, length = 255)
  private String spaceKey;

  @Column(name = "space_name", length = 255)
  private String spaceName;

  protected ConfluenceSpaceSelection() {}

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
