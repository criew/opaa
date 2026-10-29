package io.opaa.knowledge;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Library-wide configuration that applies regardless of how a document entered the library (split
 * off {@link UploadProperties}, whose {@code opaa.upload} namespace misleadingly implied this only
 * governed the REST upload endpoint, when {@link LibraryStorageQuotaService} in fact enforces it on
 * every ingestion path - upload, FILESYSTEM, HTTP_DIRECTORY, RSS_FEED and mail attachments alike).
 *
 * @param quotaBytes the operator's upper limit of the total size, summed across every document a
 *     library holds, that one library may occupy. Below it the system administration sets the
 *     house-wide quota of shared libraries ({@link SharedLibraryQuota}); without its own value, and
 *     for every private library, this limit applies alone. {@code application.yml} resolves the
 *     underlying env var to 10 GiB when unset. {@code 0} or negative means no upper limit, not a
 *     fallback to 10 GiB, so an installation with larger libraries can lift it entirely. Enforced
 *     by {@link LibraryStorageQuotaService} on every ingestion path that stores document content.
 */
@InstallationWide
@NoDataImpact("storage quotas")
@ConfigurationProperties(prefix = "opaa.library")
public record LibraryProperties(long quotaBytes) {

  public LibraryProperties {
    // quotaBytes is deliberately NOT defaulted here - see its own Javadoc above. A value <= 0 is a
    // real, supported "unbegrenzt" configuration, resolved by SharedLibraryQuota, not
    // normalized away on this record.
  }
}
