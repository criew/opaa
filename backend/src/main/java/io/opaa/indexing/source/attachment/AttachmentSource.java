package io.opaa.indexing.source.attachment;

import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * A source-agnostic description of one attachment for {@link AttachmentIndexer} to index (ADR-0022,
 * Entscheidung 8) - replaces the RSS-only {@code AttachmentCandidate} parameter the indexer used to
 * take directly.
 */
public sealed interface AttachmentSource {

  /**
   * A remote attachment the indexer must download itself, exactly what RSS ({@code
   * AttachmentProfile}) and, in the future, Confluence discover: a link, resolved into the {@link
   * HttpClient}/{@code Authorization} header the caller has already picked for it (same-origin
   * politeness/SSL relaxation is a decision only the caller's own run context can make - see {@code
   * RssFeedRunContext#httpClientFor}, not something this package re-derives).
   */
  record Download(String url, String suggestedFileName, HttpClient httpClient, String authHeader)
      implements AttachmentSource {}

  /**
   * An attachment whose bytes are already on disk, no download step needed - the case Mail needs:
   * {@code EmlReader}/{@code MsgReader} already extracted the attachment into a temporary file
   * before this path ever sees it - and the case of a source whose own access layer downloads
   * (Confluence: the download goes through {@code ConfluenceClient}, which owns the edition-aware
   * redirect policy, the request budget and the credentials; only the bytes reach this path). The
   * caller owns the file and deletes it once {@link AttachmentIndexer#indexAll} returns.
   *
   * @param fileName the attachment's own, human-readable name (e.g. {@code "anlage.pdf"}) - never
   *     used as identity, only for display and format detection, mirroring {@link
   *     Download#suggestedFileName()}
   * @param filePathIdentity the unique {@code file_path} this attachment is stored under (ADR-0022,
   *     Entscheidung 2) - distinct from {@code fileName} because a Mail attachment has no URL of
   *     its own: the caller resolves a stable, collision-free identity (e.g. the parent document's
   *     own {@code file_path} plus a positional disambiguator) before constructing this record
   * @param remoteVersion the source's own change marker for this attachment, recorded as {@code
   *     Document#getLastModifiedRemote()} so the caller's pre-download check can skip it unchanged
   *     on the next run (a Confluence attachment's version number); {@code null} for a source
   *     without one (Mail)
   */
  record LocalFile(Path file, String fileName, String filePathIdentity, String remoteVersion)
      implements AttachmentSource {

    /** A local file without a change marker of its own - the Mail case. */
    public LocalFile(Path file, String fileName, String filePathIdentity) {
      this(file, fileName, filePathIdentity, null);
    }
  }
}
