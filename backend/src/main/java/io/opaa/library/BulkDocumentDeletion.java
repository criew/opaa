package io.opaa.library;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of {@link LibraryDocumentService#deleteDocuments} (#1943). Every requested id ends up
 * in exactly one of the two lists, so a partial success is readable without comparing against the
 * request; a duplicate id is deleted once and reported once.
 *
 * @param failures the ids left standing, each with the German reason the caller is shown.
 */
public record BulkDocumentDeletion(List<UUID> deletedDocumentIds, List<Failure> failures) {

  public record Failure(UUID documentId, String message) {}
}
