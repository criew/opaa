package io.opaa.indexing.source.attachment;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.RequestPoliteness;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes the attachments of a parent document into their own {@link io.opaa.indexing.Document}
 * rows (ADR-0022) - the shared path RSS, Mail and Confluence all use. It depends on no connector
 * package: a caller supplies an {@link AttachmentAccess} and a list of {@link AttachmentSource}.
 *
 * <p>An attachment failure never propagates: a lost attachment is logged and skipped with no effect
 * on the parent's outcome, but marks {@link AttachmentAccess#markDeferred()} so a later conditional
 * {@code GET} cannot suppress the retry. Every attachment created or confirmed unchanged becomes a
 * child of {@code parentDocumentId} (Entscheidung 4) and its {@code file_path} is returned, for a
 * caller that folds those paths into its own {@code currentFilePaths} (Entscheidung 3).
 */
public class AttachmentIndexer {

  private static final Logger log = LoggerFactory.getLogger(AttachmentIndexer.class);

  /**
   * How many levels of attachment-in-attachment recursion the current thread is at - {@code null}
   * outside of any {@link #indexAll} call. An attachment whose own pipeline reports further {@code
   * discoveredAttachments} (e.g. a nested {@code .eml}) re-enters this class synchronously, through
   * {@code FileProcessingService#processUrlFile}'s own attachment handling, on the same thread. The
   * depth cutoff is this class's alone (ADR-0022, Entscheidung 6), never a pipeline's.
   */
  private static final ThreadLocal<Integer> RECURSION_DEPTH = new ThreadLocal<>();

  private final BoundedDownloader attachmentDownloader;
  private final FileProcessingService fileProcessingService;
  private final LibraryStorageQuotaService storageQuotaService;
  private final AttachmentProperties attachmentProperties;

  public AttachmentIndexer(
      BoundedDownloader attachmentDownloader,
      FileProcessingService fileProcessingService,
      LibraryStorageQuotaService storageQuotaService,
      AttachmentProperties attachmentProperties) {
    this.attachmentDownloader = attachmentDownloader;
    this.fileProcessingService = fileProcessingService;
    this.storageQuotaService = storageQuotaService;
    this.attachmentProperties = attachmentProperties;
  }

  /**
   * Indexes every attachment {@code sources} lists, up to {@link
   * AttachmentDownloadLimits#maxPerParent()}. The politeness delay applies before each {@link
   * AttachmentSource.Download} and is a no-op before an {@link AttachmentSource.LocalFile}.
   *
   * @param parentDocumentId the row every indexed attachment becomes a child of
   * @param parentPath the parent document's own {@code file_path} - recorded on every attachment
   *     via {@code sourceEntryUrl}, alongside {@code parentDocumentId}
   * @return the {@code file_path} of every attachment created or confirmed unchanged this call,
   *     never {@code null}
   */
  public List<String> indexAll(
      AttachmentAccess access,
      List<AttachmentSource> sources,
      UUID parentDocumentId,
      String parentPath,
      DocumentSourceType sourceType,
      AttachmentDownloadLimits limits) {
    if (sources.isEmpty()) {
      return List.of();
    }
    boolean topLevel = RECURSION_DEPTH.get() == null;
    int depth = topLevel ? 0 : RECURSION_DEPTH.get();
    if (depth >= attachmentProperties.maxDepth()) {
      log.warn(
          "Maximum attachment depth ({}) reached for {}, skipping {} nested attachment(s)",
          attachmentProperties.maxDepth(),
          parentPath,
          sources.size());
      access.markDeferred();
      return List.of();
    }
    if (topLevel) {
      RECURSION_DEPTH.set(0);
    }
    try {
      int limit = Math.min(sources.size(), limits.maxPerParent());
      if (sources.size() > limit) {
        log.info(
            "Parent document {} carries {} attachments, processing only the first {} (attachment"
                + " limit)",
            parentPath,
            sources.size(),
            limit);
        access.markDeferred();
      }
      List<String> indexedPaths = new ArrayList<>();
      RECURSION_DEPTH.set(depth + 1);
      try {
        for (AttachmentSource source : sources.subList(0, limit)) {
          if (source instanceof AttachmentSource.Download) {
            RequestPoliteness.delayBeforeRequest(limits.requestDelayMs());
          }
          indexOne(access, source, parentDocumentId, parentPath, sourceType, limits)
              .ifPresent(indexedPaths::add);
        }
      } finally {
        RECURSION_DEPTH.set(depth);
      }
      return List.copyOf(indexedPaths);
    } finally {
      if (topLevel) {
        RECURSION_DEPTH.remove();
      }
    }
  }

  private Optional<String> indexOne(
      AttachmentAccess access,
      AttachmentSource source,
      UUID parentDocumentId,
      String parentPath,
      DocumentSourceType sourceType,
      AttachmentDownloadLimits limits) {
    return switch (source) {
      case AttachmentSource.Download download ->
          indexDownload(access, download, parentDocumentId, parentPath, sourceType, limits);
      case AttachmentSource.LocalFile localFile ->
          indexLocalFile(access, localFile, parentDocumentId, parentPath, sourceType);
    };
  }

  private Optional<String> indexDownload(
      AttachmentAccess access,
      AttachmentSource.Download download,
      UUID parentDocumentId,
      String parentPath,
      DocumentSourceType sourceType,
      AttachmentDownloadLimits limits) {
    BoundedDownloader.DownloadedFile downloaded = null;
    try {
      downloaded =
          attachmentDownloader.downloadBounded(
              download.httpClient(),
              download.url(),
              download.suggestedFileName(),
              limits.maxAttachmentSizeBytes(),
              limits.userAgent(),
              download.authHeader());

      String contentType = downloaded.contentType();
      if (isHtmlContentType(contentType)) {
        // An HTML response on what the caller identified as an attachment link - a bot-protection
        // challenge or a 200-status error page - must never be trusted just because the URL
        // carried a supported extension.
        log.info(
            "Skipping attachment that answered with HTML instead of a document (likely a"
                + " bot-protection or error page): {} (from {})",
            download.url(),
            parentPath);
        access
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                "Anlage antwortete mit HTML statt einem Dokument (vermutlich Bot-Schutz)",
                download.url());
        access.markDeferred();
        return Optional.empty();
      }

      // The GSB profile's candidates carry no extension in their URL - resolved here, once the
      // response's actual Content-Type is known. Only a display name / hint from here on; the
      // accept/reject decision below is made from the downloaded bytes.
      String fileName = resolveFileName(download.suggestedFileName(), contentType);

      // Caught here, not by the broader catch (IOException | InterruptedException e) below - that
      // one reports "Anlage nicht erreichbar", which would be misleading for a read failure on a
      // file already downloaded; the remote end answered just fine.
      String detectedMimeType;
      try {
        detectedMimeType = SupportedDocumentFormats.detectMediaType(downloaded.path());
      } catch (IOException e) {
        log.warn(
            "Could not read downloaded attachment to detect its format, skipping: {} (from {})",
            download.url(),
            parentPath,
            e);
        access
            .events()
            .record(
                IndexingEventCategory.ERROR,
                "Anlage konnte nach dem Herunterladen nicht auf ihr Format geprüft werden",
                download.url());
        access.markDeferred();
        return Optional.empty();
      }
      SupportedDocumentFormats.ContentDecision decision =
          SupportedDocumentFormats.decideForFileName(fileName, detectedMimeType);
      if (!decision.supported()) {
        log.info(
            "Skipping attachment with an unsupported format: {} (from {}, Content-Type {})",
            download.url(),
            parentPath,
            contentType);
        access
            .events()
            .record(
                IndexingEventCategory.UNSUPPORTED_FORMAT,
                "Anlagenformat wird nicht unterstützt",
                download.url());
        access.markDeferred();
        return Optional.empty();
      }
      if (decision.extensionMismatch()) {
        // Indexed anyway, only reported.
        access
            .events()
            .record(
                IndexingEventCategory.FORMAT_MISMATCH,
                "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                    + decision.detectedExtension()
                    + ")",
                download.url());
      }

      // Files.probeContentType inside FileProcessingService#processUrlFile probes the physical
      // temp file, which for a GSB attachment carries no extension (".tmp") - renaming it to match
      // the resolved name's extension lets that probe succeed.
      Path indexedFile = withMatchingExtension(downloaded.path(), fileName);
      long size = Files.size(indexedFile);
      return storeAttachment(
          access,
          indexedFile,
          fileName,
          download.url(),
          null,
          size,
          parentDocumentId,
          parentPath,
          sourceType);
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      log.warn(
          "Skipping attachment exceeding the size limit of {} bytes: {} (from {})",
          limits.maxAttachmentSizeBytes(),
          download.url(),
          parentPath);
      access
          .events()
          .record(
              IndexingEventCategory.REJECTED,
              "Anlage überschreitet die zulässige Größe",
              download.url());
      access.markDeferred();
    } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
      log.warn(
          "Attachment redirected to a foreign host, skipping: {} (from {}, {})",
          download.url(),
          parentPath,
          e.getMessage());
      access
          .events()
          .record(IndexingEventCategory.REJECTED, e.userMessage() + " (Anlage)", download.url());
      access.markDeferred();
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      log.warn(
          "Attachment target rejected, skipping: {} (from {}, {})",
          download.url(),
          parentPath,
          e.getMessage());
      access
          .events()
          .record(IndexingEventCategory.REJECTED, e.getMessage() + " (Anlage)", download.url());
      access.markDeferred();
    } catch (IOException | InterruptedException e) {
      log.warn(
          "Attachment unreachable, skipping: {} (from {}, {})",
          download.url(),
          parentPath,
          e.getMessage());
      access
          .events()
          .record(IndexingEventCategory.UNREACHABLE, "Anlage nicht erreichbar", download.url());
      access.markDeferred();
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error("Failed to process attachment: {} (from {})", download.url(), parentPath, e);
      access
          .events()
          .record(
              IndexingEventCategory.ERROR,
              "Verarbeitung der Anlage fehlgeschlagen",
              download.url());
      access.markDeferred();
    } finally {
      if (downloaded != null) {
        try {
          Files.deleteIfExists(downloaded.path());
        } catch (IOException e) {
          log.warn("Failed to delete temp file: {}", downloaded.path(), e);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Indexes an attachment whose bytes are already on disk - the case Mail and Confluence need, no
   * download step involved here. {@code localFile.filePathIdentity()} is this attachment's {@code
   * file_path} (ADR-0022, Entscheidung 2); {@code localFile.fileName()} is only its display name.
   */
  private Optional<String> indexLocalFile(
      AttachmentAccess access,
      AttachmentSource.LocalFile localFile,
      UUID parentDocumentId,
      String parentPath,
      DocumentSourceType sourceType) {
    try {
      String detectedMimeType = SupportedDocumentFormats.detectMediaType(localFile.file());
      SupportedDocumentFormats.ContentDecision decision =
          SupportedDocumentFormats.decideForFileName(localFile.fileName(), detectedMimeType);
      if (!decision.supported()) {
        log.info(
            "Skipping local attachment with an unsupported format: {} (from {})",
            localFile.fileName(),
            parentPath);
        access
            .events()
            .record(
                IndexingEventCategory.UNSUPPORTED_FORMAT,
                "Anlagenformat wird nicht unterstützt",
                localFile.fileName());
        return Optional.empty();
      }
      if (decision.extensionMismatch()) {
        access
            .events()
            .record(
                IndexingEventCategory.FORMAT_MISMATCH,
                "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                    + decision.detectedExtension()
                    + ")",
                localFile.fileName());
      }
      long size = Files.size(localFile.file());
      return storeAttachment(
          access,
          localFile.file(),
          localFile.fileName(),
          localFile.filePathIdentity(),
          localFile.remoteVersion(),
          size,
          parentDocumentId,
          parentPath,
          sourceType);
    } catch (IOException e) {
      log.warn(
          "Failed to read local attachment, skipping: {} (from {})",
          localFile.fileName(),
          parentPath,
          e);
      access
          .events()
          .record(
              IndexingEventCategory.ERROR,
              "Anlage konnte nicht gelesen werden",
              localFile.fileName());
      // See storeAttachment's own comment: present in the parent, only not readable this run.
      access.recordIndexedAttachment(localFile.filePathIdentity(), false);
      return Optional.empty();
    }
  }

  /**
   * The {@link FileProcessingService#processUrlFile} call and outcome handling both branches share.
   * {@code remoteVersion} is the source's change marker for the attachment ({@link
   * AttachmentSource.LocalFile#remoteVersion()}), {@code null} for a download; {@code access}
   * carries the parent's {@link AttachmentAccess#sourceContext()} to the attachment.
   */
  private Optional<String> storeAttachment(
      AttachmentAccess access,
      Path localFile,
      String fileName,
      String filePathIdentity,
      String remoteVersion,
      long size,
      UUID parentDocumentId,
      String parentPath,
      DocumentSourceType sourceType) {
    try {
      FileProcessingResult result =
          fileProcessingService.processUrlFile(
              localFile,
              fileName,
              filePathIdentity,
              remoteVersion,
              size,
              access.targetLibrary(),
              sourceType,
              parentPath,
              parentDocumentId,
              access);
      if (result == FileProcessingResult.QUOTA_EXCEEDED) {
        // Deferred, not recordSkipped: an attachment was never a discrete unit of the run's own
        // total, so there is nothing to mark skipped - only the deferred flag, so a caller with a
        // conditional-GET retries it on a future run.
        access
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(access.targetLibrary().getId()),
                filePathIdentity);
        access.markDeferred();
        // Still present in the parent, just not (re)processed this run - without this a transient
        // failure of an already-indexed attachment of a re-parsed parent would let the caller's
        // vanished-cleanup delete its row permanently (a checksum-skipped parent never retries).
        access.recordIndexedAttachment(filePathIdentity, false);
        return Optional.empty();
      }
      if (result == FileProcessingResult.NO_EXTRACTABLE_TEXT) {
        // The document was already rejected and marked FAILED - not deferred: unlike a transient
        // quota/availability issue, a scan PDF will not gain a text layer on retry.
        access
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
                filePathIdentity);
        access.recordIndexedAttachment(filePathIdentity, false);
        return Optional.empty();
      }
      if (result == FileProcessingResult.FAILED) {
        access
            .events()
            .record(
                IndexingEventCategory.ERROR,
                "Verarbeitung der Anlage fehlgeschlagen",
                filePathIdentity);
        access.markDeferred();
        access.recordIndexedAttachment(filePathIdentity, false);
        return Optional.empty();
      }
      // An unchanged attachment (same checksum as an already-indexed document) is deduplicated by
      // processUrlFile itself and returns SKIPPED - must not inflate the document count again, but
      // it is still an attachment this run confirmed present.
      if (result == FileProcessingResult.PROCESSED) {
        access.progress().recordDocumentIndexed();
      }
      access.recordIndexedAttachment(filePathIdentity, result == FileProcessingResult.PROCESSED);
      log.info("Indexed attachment: {} (from {})", filePathIdentity, parentPath);
      return Optional.of(filePathIdentity);
    } catch (IOException e) {
      log.error("Failed to process attachment: {} (from {})", filePathIdentity, parentPath, e);
      access
          .events()
          .record(
              IndexingEventCategory.ERROR,
              "Verarbeitung der Anlage fehlgeschlagen",
              filePathIdentity);
      access.markDeferred();
      access.recordIndexedAttachment(filePathIdentity, false);
      return Optional.empty();
    }
  }

  /**
   * Appends an extension derived from {@code contentType} when {@code suggestedFileName} carries
   * none at all - the Government Site Builder case, a no-op for {@link AttachmentProfile#GENERIC}.
   * Checks {@code AttachmentProfile.fileHasSomeExtension}, not {@link
   * SupportedDocumentFormats#isSupported}, so a candidate with an unrecognized extension gets no
   * second one. From here on only the detected content decides acceptance.
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
