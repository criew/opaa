package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of one Sammelzuweisung: documents changed (one audit event each), documents that already
 * held the value, ids rejected because they are not documents of the addressed library, and the
 * {@code correlationRef} all events of the call share.
 */
public record BulkMetadataResult(
    int updatedCount, int unchangedCount, List<UUID> rejectedDocumentIds, String correlationRef) {}
