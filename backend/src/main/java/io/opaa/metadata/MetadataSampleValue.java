package io.opaa.metadata;

import io.opaa.api.types.MetadataOrigin;

/**
 * One stored value of a sampled document with its provenance - a row of the handausgewertete
 * Stichprobe (ADR-0024).
 *
 * @param value {@code null} for a value marked "kein Wert ermittelbar"
 */
public record MetadataSampleValue(
    String fieldKey,
    String label,
    String value,
    MetadataOrigin origin,
    Double confidence,
    String modelId) {}
