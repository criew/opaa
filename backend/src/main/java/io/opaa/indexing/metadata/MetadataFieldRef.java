package io.opaa.indexing.metadata;

import java.util.UUID;

/**
 * Either a core field or a library field, reduced to what everything keyed on {@code field_key}
 * needs: the stored key, the German label for display and audit, and - for a library field - the
 * field id the value row must reference. The one place the two kinds of field meet, so that
 * setting, deleting, auditing and counting a value stay one code path.
 *
 * @param libraryFieldId {@code null} for a core field
 */
public record MetadataFieldRef(String key, String label, UUID libraryFieldId) {

  public static MetadataFieldRef of(CoreMetadataField field) {
    return new MetadataFieldRef(field.key(), field.label(), null);
  }

  public static MetadataFieldRef of(LibraryMetadataField field) {
    return new MetadataFieldRef(field.documentFieldKey(), field.getLabel(), field.getId());
  }

  public boolean isLibraryField() {
    return libraryFieldId != null;
  }

  /** Whether a change to this field can move a chunk metadata key - the title never does. */
  public boolean affectsChunkKeys() {
    return isLibraryField() || !CoreMetadataField.TITLE.key().equals(key);
  }
}
