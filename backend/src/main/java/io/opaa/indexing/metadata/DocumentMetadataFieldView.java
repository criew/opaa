package io.opaa.indexing.metadata;

/**
 * One core field of a document as the metadata view shows it: the field, its current row ({@code
 * null} when empty), the German display form and, for a manual value, the display name of the
 * person who set it ({@code null} when the account no longer exists).
 */
public record DocumentMetadataFieldView(
    CoreMetadataField field,
    MetadataValueSnapshot value,
    String displayValue,
    String actorDisplayName) {}
