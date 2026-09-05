package io.opaa.indexing.source.confluence;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The attachment half of a page visit: lists a page's attachments and hands each changed one to the
 * generalized attachment path (ADR-0022). The download stays with the edition-aware {@link
 * ConfluenceClient}, which owns the credentials, the redirect policy Cloud's media service needs,
 * the request budget and the meter; everything after the bytes is {@link AttachmentIndexer}'s, the
 * outcome count included.
 */
final class ConfluenceAttachmentIndexing {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceAttachmentIndexing.class);

  private final AttachmentIndexer attachmentIndexer;
  private final DocumentRepository documentRepository;

  ConfluenceAttachmentIndexing(
      AttachmentIndexer attachmentIndexer, DocumentRepository documentRepository) {
    this.attachmentIndexer = attachmentIndexer;
    this.documentRepository = documentRepository;
  }

  /**
   * Lists and indexes the attachments of one page. Every listed attachment is marked present
   * whether or not it is (re)indexed; the page is marked reprocessed once its list was fetched, so
   * an attachment missing from it is a deletion finding for the reconciliation. An unchanged
   * attachment (version) is skipped before any download; an unlistable page leaves the listing
   * incomplete, since its attachments would otherwise look vanished.
   *
   * @param pageDocumentId the page's own row, the parent of every attachment (ADR-0022,
   *     Entscheidung 4); {@code null} for a page without a row of its own
   */
  void indexAttachments(
      ConfluenceRun run,
      String pageId,
      String pagePath,
      UUID pageDocumentId,
      SourceDocumentContext context)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    List<ConfluenceAttachment> attachments;
    try {
      attachments = run.client.listAttachments(pageId);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException e) {
      run.events.record(
          IndexingEventCategory.UNREACHABLE,
          "Anhänge nicht auflistbar: " + e.getMessage(),
          pagePath);
      run.listingComplete = false;
      if (context.containerKey() != null) {
        run.unreadableSpaceKeys.add(context.containerKey());
      }
      return;
    }
    run.frame.markReprocessed(pagePath);
    for (ConfluenceAttachment attachment : attachments) {
      String path = attachment.stableUrl();
      run.frame.markPresent(path);
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndFilePath(run.library.getId(), path);
      if (existing.isPresent()
          && existing.get().isUnchangedAt(String.valueOf(attachment.version()))) {
        run.progress.recordAttachment(AttachmentOutcome.SKIPPED);
        continue;
      }
      indexAttachment(run, attachment, path, pagePath, pageDocumentId, context);
    }
  }

  /**
   * The download is bounded by {@link ConfluenceProperties#maxAttachmentSizeBytes()} before the
   * path ever sees the bytes, and one attachment is handed over per call (the request budget
   * bounds a run, not a per-page cap) - so the shared attachment limits apply, none of Confluence's
   * own.
   */
  private void indexAttachment(
      ConfluenceRun run,
      ConfluenceAttachment attachment,
      String path,
      String pagePath,
      UUID pageDocumentId,
      SourceDocumentContext context)
      throws InterruptedException, ConfluenceAccessException.BudgetExhausted {
    BoundedDownloader.DownloadedFile downloaded = null;
    try {
      downloaded = run.client.downloadAttachment(attachment);
      attachmentIndexer.indexAll(
          run.frame.attachmentAccess(context),
          List.of(
              new AttachmentSource.LocalFile(
                  downloaded.path(),
                  attachment.fileName(),
                  path,
                  String.valueOf(attachment.version()))),
          pageDocumentId,
          pagePath,
          DocumentSourceType.CONFLUENCE);
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      run.events.record(
          IndexingEventCategory.REJECTED, "Anhang überschreitet die Größengrenze", path);
      run.progress.recordAttachment(AttachmentOutcome.SKIPPED);
    } catch (ConfluenceAccessException.BudgetExhausted e) {
      throw e;
    } catch (ConfluenceAccessException e) {
      run.events.record(IndexingEventCategory.UNREACHABLE, e.getMessage(), path);
      run.progress.recordAttachment(AttachmentOutcome.FAILED);
    } finally {
      if (downloaded != null) {
        try {
          Files.deleteIfExists(downloaded.path());
        } catch (IOException e) {
          log.debug("Could not delete temporary attachment file {}", downloaded.path(), e);
        }
      }
    }
  }
}
