package io.opaa.indexing.document;

import io.opaa.format.DocumentService;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import java.util.function.Supplier;

/**
 * The protocol entry each {@link DocumentIngestResult} calls for - the German texts stand here
 * once, for a run's own items and for attachments alike.
 */
public final class DocumentIngestOutcomes {

  public static final String FAILED_MESSAGE = "Verarbeitung fehlgeschlagen";
  public static final String ATTACHMENT_FAILED_MESSAGE = "Verarbeitung der Anlage fehlgeschlagen";

  private DocumentIngestOutcomes() {}

  /**
   * The texts of a quota rejection, each resolved only when needed: {@code library} for {@code
   * QUOTA_EXCEEDED}, {@code person} for {@code PERSONAL_QUOTA_EXCEEDED}.
   */
  public record QuotaMessages(Supplier<String> library, Supplier<String> person) {

    /** The messages of {@code library}'s own quota and of its owner's private storage. */
    public static QuotaMessages of(LibraryStorageQuotaService quota, KnowledgeLibrary library) {
      return new QuotaMessages(
          () -> quota.quotaExceededMessage(library.getId()),
          () -> quota.personalQuotaExceededMessage(library));
    }
  }

  /**
   * Records the entry {@code result} calls for: {@code QUOTA_EXCEEDED}, {@code
   * PERSONAL_QUOTA_EXCEEDED} and {@code NO_EXTRACTABLE_TEXT} are rejections, {@code FAILED} an
   * error carrying {@code failedMessage}; {@code PROCESSED} and {@code SKIPPED} record nothing.
   */
  public static void record(
      IndexingEventSink events,
      DocumentIngestResult result,
      String reference,
      QuotaMessages quotaMessages,
      String failedMessage) {
    switch (result) {
      case QUOTA_EXCEEDED ->
          events.record(IndexingEventCategory.REJECTED, quotaMessages.library().get(), reference);
      case PERSONAL_QUOTA_EXCEEDED ->
          events.record(IndexingEventCategory.REJECTED, quotaMessages.person().get(), reference);
      case NO_EXTRACTABLE_TEXT ->
          events.record(
              IndexingEventCategory.REJECTED,
              DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
              reference);
      case FAILED -> events.record(IndexingEventCategory.ERROR, failedMessage, reference);
      case PROCESSED, SKIPPED -> {}
    }
  }
}
