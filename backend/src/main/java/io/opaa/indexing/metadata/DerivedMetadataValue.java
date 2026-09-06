package io.opaa.indexing.metadata;

import java.util.UUID;

/**
 * One value the model-backed extraction accepted (metadata-schema.md, Schritt 2): already checked
 * against the offered vocabulary and the confidence threshold, so storing it is the only step left.
 *
 * @param libraryValueId the chosen list entry of a library SELECT field, {@code null} for the
 *     Kernfeld Dokumentart, whose codes live in the shared vocabulary instead
 */
public record DerivedMetadataValue(
    MetadataFieldRef field, String code, UUID libraryValueId, String modelId, double confidence) {}
