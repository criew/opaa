package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;

/**
 * The Stichproben-Export of one library, ordered by document id so a repeat run examines the same
 * documents (metadata-schema.md, "Messung und Abnahme", Punkt 3).
 *
 * @param size the requested size; {@code documents} is shorter when the library holds fewer
 */
public record LibraryMetadataSample(
    UUID libraryId, int size, List<MetadataSampleDocument> documents) {}
