package io.opaa.indexing;

import java.util.function.Supplier;

/**
 * The protocol entry each {@link FileProcessingResult} calls for - the German texts stand here
 * once, for a run's own items and for attachments alike.
 */
public final class FileProcessingOutcomes {

  public static final String FAILED_MESSAGE = "Verarbeitung fehlgeschlagen";
  public static final String ATTACHMENT_FAILED_MESSAGE = "Verarbeitung der Anlage fehlgeschlagen";

  private FileProcessingOutcomes() {}

  /**
   * Records the entry {@code result} calls for: {@code QUOTA_EXCEEDED} and {@code
   * NO_EXTRACTABLE_TEXT} are rejections, {@code FAILED} an error carrying {@code failedMessage};
   * {@code PROCESSED} and {@code SKIPPED} record nothing. {@code quotaMessage} is resolved only
   * when needed.
   */
  public static void record(
      IndexingEventSink events,
      FileProcessingResult result,
      String reference,
      Supplier<String> quotaMessage,
      String failedMessage) {
    switch (result) {
      case QUOTA_EXCEEDED ->
          events.record(IndexingEventCategory.REJECTED, quotaMessage.get(), reference);
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
