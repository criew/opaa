package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads and indexes the attachment candidates {@link DetailPageExtractor} finds on an RSS
 * entry's detail page, split out of {@link RssFeedIndexingExecutor}. Package-private - an
 * implementation detail of the executor, not a new public API.
 *
 * <p>Never lets an attachment failure propagate to the caller: a lost attachment (too large,
 * unreachable, rejected, unsupported format, or cut off by the per-entry limit) is logged and
 * skipped, with no effect on the entry's own processed/skipped/failed outcome - but marks {@code
 * ctx.anyEntryDeferred()}, so a future run's conditional {@code GET} is not allowed to suppress a
 * retry of the lost attachment.
 */
class AttachmentIndexer {

  private static final Logger log = LoggerFactory.getLogger(AttachmentIndexer.class);

  private final BoundedDownloader attachmentDownloader;
  private final FileProcessingService fileProcessingService;
  private final LibraryStorageQuotaService storageQuotaService;
  private final IndexingProperties.Rss properties;

  AttachmentIndexer(
      BoundedDownloader attachmentDownloader,
      FileProcessingService fileProcessingService,
      LibraryStorageQuotaService storageQuotaService,
      IndexingProperties.Rss properties) {
    this.attachmentDownloader = attachmentDownloader;
    this.fileProcessingService = fileProcessingService;
    this.storageQuotaService = storageQuotaService;
    this.properties = properties;
  }

  /**
   * Downloads and indexes every attachment {@code candidates} lists, up to {@link
   * IndexingProperties.Rss#maxAttachmentsPerEntry()}. The politeness delay ({@link
   * IndexingProperties.Rss#requestDelayMs()}) applies between attachments the same way it applies
   * between detail-page requests - OPAA does not operate the servers an RSS feed points at.
   */
  void indexAll(RssFeedRunContext ctx, List<AttachmentCandidate> candidates, String entryUrl) {
    int limit = Math.min(candidates.size(), properties.maxAttachmentsPerEntry());
    if (candidates.size() > limit) {
      log.info(
          "RSS entry {} carries {} attachments, processing only the first {}"
              + " (opaa.indexing.rss.max-attachments-per-entry)",
          entryUrl,
          candidates.size(),
          limit);
      ctx.anyEntryDeferred().set(true);
    }
    for (AttachmentCandidate candidate : candidates.subList(0, limit)) {
      RssPoliteness.delayBeforeRequest(properties.requestDelayMs());
      indexOne(ctx, candidate, entryUrl);
    }
  }

  private void indexOne(RssFeedRunContext ctx, AttachmentCandidate candidate, String entryUrl) {
    BoundedDownloader.DownloadedFile downloaded = null;
    try {
      // An attachment candidate's own URL is content the feed operator controls, exactly like an
      // entry's <link> (see RssFeedRunContext#httpClientFor) - sourceInsecureSsl must not weaken
      // certificate validation once it points off the feed's own origin.
      HttpClient client = ctx.httpClientFor(candidate.url());
      downloaded =
          attachmentDownloader.downloadBounded(
              client,
              candidate.url(),
              candidate.suggestedFileName(),
              properties.maxAttachmentSizeBytes(),
              properties.userAgent(),
              ctx.authHeaderFor(candidate.url()));

      String contentType = downloaded.contentType();
      if (isHtmlContentType(contentType)) {
        // An HTML response on what a profile identified as an attachment link - a bot-protection
        // challenge or a 200-status error page - must never be trusted just because the URL
        // carried a supported extension.
        log.info(
            "Skipping RSS attachment that answered with HTML instead of a document (likely a"
                + " bot-protection or error page): {} (from entry {})",
            candidate.url(),
            entryUrl);
        ctx.events()
            .record(
                IndexingEventCategory.REJECTED,
                "Anlage antwortete mit HTML statt einem Dokument (vermutlich Bot-Schutz)",
                candidate.url());
        ctx.anyEntryDeferred().set(true);
        return;
      }

      // The GSB profile's candidates carry no extension in their URL - resolved here, once the
      // response's actual Content-Type is known. Only a display name / hint from here on; the
      // accept/reject decision below is made from the downloaded bytes.
      String fileName = resolveFileName(candidate.suggestedFileName(), contentType);

      // Caught here, not by the broader catch (IOException | InterruptedException e) below - that
      // one reports "Anlage nicht erreichbar", which would be misleading for a read failure on a
      // file already downloaded; the remote end answered just fine.
      String detectedMimeType;
      try {
        detectedMimeType = SupportedDocumentFormats.detectMediaType(downloaded.path());
      } catch (IOException e) {
        log.warn(
            "Could not read downloaded RSS attachment to detect its format, skipping: {} (from"
                + " entry {})",
            candidate.url(),
            entryUrl,
            e);
        ctx.events()
            .record(
                IndexingEventCategory.ERROR,
                "Anlage konnte nach dem Herunterladen nicht auf ihr Format geprüft werden",
                candidate.url());
        ctx.anyEntryDeferred().set(true);
        return;
      }
      SupportedDocumentFormats.ContentDecision decision =
          SupportedDocumentFormats.decideForFileName(fileName, detectedMimeType);
      if (!decision.supported()) {
        log.info(
            "Skipping RSS attachment with an unsupported format: {} (from entry {}, Content-Type"
                + " {})",
            candidate.url(),
            entryUrl,
            contentType);
        ctx.events()
            .record(
                IndexingEventCategory.UNSUPPORTED_FORMAT,
                "Anlagenformat wird nicht unterstützt",
                candidate.url());
        ctx.anyEntryDeferred().set(true);
        return;
      }
      if (decision.extensionMismatch()) {
        // Indexed anyway, only reported.
        ctx.events()
            .record(
                IndexingEventCategory.FORMAT_MISMATCH,
                "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                    + decision.detectedExtension()
                    + ")",
                candidate.url());
      }

      // Files.probeContentType inside FileProcessingService#processUrlFile probes the physical
      // temp file, which for a GSB attachment carries no extension (".tmp") - renaming it to match
      // the resolved name's extension lets that probe succeed.
      Path indexedFile = withMatchingExtension(downloaded.path(), fileName);

      long size = Files.size(indexedFile);
      FileProcessingResult result =
          fileProcessingService.processUrlFile(
              indexedFile,
              fileName,
              candidate.url(),
              null,
              size,
              ctx.targetLibrary(),
              DocumentSourceType.RSS_FEED,
              entryUrl);
      if (result == FileProcessingResult.QUOTA_EXCEEDED) {
        // Deferred, not recordSkipped: an attachment was never a discrete unit of the run's own
        // total, so there is nothing to mark skipped - only the feed's ETag persistence to defer
        // so a future run retries it.
        ctx.events()
            .record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(ctx.targetLibrary().getId()),
                candidate.url());
        ctx.anyEntryDeferred().set(true);
        return;
      }
      if (result == FileProcessingResult.NO_EXTRACTABLE_TEXT) {
        // The document was already rejected and marked FAILED (DocumentService#isTextlessPdf) -
        // not deferred: unlike a transient quota/availability issue, a scan PDF will not gain a
        // text layer on retry.
        ctx.events()
            .record(
                IndexingEventCategory.REJECTED,
                DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
                candidate.url());
        return;
      }
      // An unchanged attachment (same checksum as an already-indexed document) is deduplicated by
      // processUrlFile itself and returns SKIPPED - must not inflate the document count again.
      if (result == FileProcessingResult.PROCESSED) {
        ctx.progress().recordDocumentIndexed();
      }
      log.info("Indexed RSS attachment: {} (from entry {})", candidate.url(), entryUrl);
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      log.warn(
          "Skipping RSS attachment exceeding the size limit of {} bytes: {} (from entry {})",
          properties.maxAttachmentSizeBytes(),
          candidate.url(),
          entryUrl);
      ctx.events()
          .record(
              IndexingEventCategory.REJECTED,
              "Anlage überschreitet die zulässige Größe",
              candidate.url());
      ctx.anyEntryDeferred().set(true);
    } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
      log.warn(
          "RSS attachment redirected to a foreign host, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      ctx.events()
          .record(IndexingEventCategory.REJECTED, e.userMessage() + " (Anlage)", candidate.url());
      ctx.anyEntryDeferred().set(true);
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      log.warn(
          "RSS attachment target rejected, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      ctx.events()
          .record(IndexingEventCategory.REJECTED, e.getMessage() + " (Anlage)", candidate.url());
      ctx.anyEntryDeferred().set(true);
    } catch (IOException | InterruptedException e) {
      log.warn(
          "RSS attachment unreachable, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      ctx.events()
          .record(IndexingEventCategory.UNREACHABLE, "Anlage nicht erreichbar", candidate.url());
      ctx.anyEntryDeferred().set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error(
          "Failed to process RSS attachment: {} (from entry {})", candidate.url(), entryUrl, e);
      ctx.events()
          .record(
              IndexingEventCategory.ERROR,
              "Verarbeitung der Anlage fehlgeschlagen",
              candidate.url());
      ctx.anyEntryDeferred().set(true);
    } finally {
      if (downloaded != null) {
        try {
          Files.deleteIfExists(downloaded.path());
        } catch (IOException e) {
          log.warn("Failed to delete temp file: {}", downloaded.path(), e);
        }
      }
    }
  }

  /**
   * Appends an extension derived from {@code contentType} when {@code suggestedFileName} carries no
   * extension at all (the Government Site Builder profile's case) - a no-op for {@link
   * AttachmentProfile#GENERIC} candidates, which always already carry one. Checks {@code
   * AttachmentProfile.fileHasSomeExtension}, not {@link SupportedDocumentFormats#isSupported}: a
   * GENERIC candidate can carry an extension {@link SupportedDocumentFormats} does not recognize
   * (e.g. {@code bescheid.csv}), and must not get a second, content-type-derived extension appended
   * on top. Only a name with no extension whatsoever gets one synthesized here; from here on, only
   * the actually detected content - never this declared, server-asserted {@code contentType} -
   * decides acceptance.
   *
   * <p>Known gap: a GSB attachment (no URL extension) that mislabels a non-text response as {@code
   * Content-Type: text/plain} is still trusted for the text-tolerant acceptance branch of {@link
   * SupportedDocumentFormats#decideForFileName}, since the declared header is the only hint
   * available for an extension-less address.
   */
  private static String resolveFileName(String suggestedFileName, String contentType) {
    if (AttachmentProfile.fileHasSomeExtension(suggestedFileName)) {
      return suggestedFileName;
    }
    String extension = SupportedDocumentFormats.extensionForContentType(contentType);
    if (extension == null) {
      return suggestedFileName;
    }
    String baseName =
        suggestedFileName == null || suggestedFileName.isBlank() ? "attachment" : suggestedFileName;
    return baseName + extension;
  }

  /**
   * Renames {@code tempFile} to a new temp file carrying {@code fileName}'s own extension, when it
   * does not already have it. A no-op when the extension already matches, which covers every {@link
   * AttachmentProfile#GENERIC} attachment.
   */
  private static Path withMatchingExtension(Path tempFile, String fileName) throws IOException {
    String desiredSuffix = extractExtension(fileName);
    if (tempFile.toString().toLowerCase(Locale.ROOT).endsWith(desiredSuffix)) {
      return tempFile;
    }
    Path renamed = Files.createTempFile("opaa-", desiredSuffix);
    Files.move(tempFile, renamed, StandardCopyOption.REPLACE_EXISTING);
    return renamed;
  }

  private static String extractExtension(String fileName) {
    if (fileName == null) {
      return ".tmp";
    }
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex >= 0) {
      return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
    return ".tmp";
  }

  /** Whether {@code contentType} (the raw {@code Content-Type} header value) denotes HTML. */
  private static boolean isHtmlContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml");
  }
}
