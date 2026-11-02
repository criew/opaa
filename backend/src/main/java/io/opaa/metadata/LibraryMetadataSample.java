package io.opaa.metadata;

import java.util.List;
import java.util.UUID;

/**
 * The Stichproben-Export of one library, ordered by document id so a repeat run examines the same
 * documents (ADR-0024).
 *
 * @param size the requested size; {@code documents} is shorter when the library holds fewer
 */
public record LibraryMetadataSample(
    UUID libraryId, int size, List<MetadataSampleDocument> documents) {}
