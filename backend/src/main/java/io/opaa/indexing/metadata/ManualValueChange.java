package io.opaa.indexing.metadata;

/**
 * What one manual set or delete did to one field: the row before ({@code null} when the field was
 * empty), the row after ({@code null} when it is empty now) and whether anything was written at all
 * - an identical manual value already in place is not a change and gets no audit event.
 */
public record ManualValueChange(
    MetadataValueSnapshot before, MetadataValueSnapshot after, boolean changed) {

  static ManualValueChange unchanged(MetadataValueSnapshot current) {
    return new ManualValueChange(current, current, false);
  }
}
