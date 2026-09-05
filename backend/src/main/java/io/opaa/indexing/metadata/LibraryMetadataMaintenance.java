package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;

/**
 * The Pflege-Anker of one library: its indexed bestand and, per core field in schema order, how
 * many documents of that bestand still have no value. Built at query time in the rights context of
 * the asking person - never precomputed, never cached (metadata-schema.md, Rechte-Invariante).
 */
public record LibraryMetadataMaintenance(
    UUID libraryId, long totalDocuments, List<MetadataFieldMaintenance> fields) {}
