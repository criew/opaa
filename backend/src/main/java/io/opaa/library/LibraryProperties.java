package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Library-wide configuration that applies regardless of how a document entered the library (#1273:
 * split off {@link UploadProperties}, whose {@code opaa.upload} namespace misleadingly implied this
 * only governed the REST upload endpoint, when {@link LibraryStorageQuotaService} in fact enforces
 * it on every ingestion path - upload, FILESYSTEM, HTTP_DIRECTORY, RSS_FEED and mail attachments
 * alike).
 *
 * @param quotaBytes the maximum total size, summed across every document a library holds, that
 *     library may occupy (#119, Maintainer-Entscheidung: Standardkontingent je Bibliothek). {@code
 *     application.yml}'s own default resolves the underlying env var to 10 GiB (10 737 418 240)
 *     when unset - generous for a working knowledge library while still bounding how much
 *     disk/vector-store space a single library - upload or connector-fed alike - can consume
 *     unchecked. <b>Deliberately not defaulted here (PR #700 review, finding 2):</b> {@code 0} or
 *     negative means <em>unbegrenzt</em> (no quota enforced at all), not "fall back to 10 GiB" - an
 *     operator with an existing library already larger than 10 GiB must be able to opt out of the
 *     new limit entirely rather than have every upload and connector document into it rejected the
 *     moment this version starts. Enforced by {@link LibraryStorageQuotaService} at every ingestion
 *     path that stores document content (upload via {@link LibraryDocumentService}, and the
 *     FILESYSTEM/HTTP_DIRECTORY/RSS_FEED connector paths via {@code
 *     io.opaa.indexing.FileProcessingService}), not merely the upload endpoint - a connector run
 *     can grow a library's bestand just as much as a human upload can.
 */
@ConfigurationProperties(prefix = "opaa.library")
public record LibraryProperties(long quotaBytes) {

  public LibraryProperties {
    // quotaBytes is deliberately NOT defaulted here - see its own Javadoc above. A value <= 0 is a
    // real, supported "unbegrenzt" configuration, resolved by LibraryStorageQuotaService, not
    // normalized away on this record.
  }
}
