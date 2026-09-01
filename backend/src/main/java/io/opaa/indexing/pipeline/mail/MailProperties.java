package io.opaa.indexing.pipeline.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link MailDocumentPipeline} applies to an EML/MSG message - memory-safety
 * ceilings, not part of the Zuschnitt itself (docs/features/ingestion-pipelines.md,
 * Querschnittsregel c).
 *
 * <p>{@link #maxAttachmentBytes} bounds disk and downstream processing, not the in-memory parse
 * itself: both readers build their whole DOM before this pipeline's own code ever runs, so {@link
 * #maxMessageBytes} - checked against the message file's own size before either reader runs - is
 * the actual memory guard.
 *
 * @param maxAttachmentDepth how many levels of nested mail-in-mail attachments {@link
 *     MailDocumentPipeline} descends into before it stops recursing and skips the remainder.
 *     Default 5.
 * @param maxAttachmentsPerMessage the maximum number of attachments a single message's own reader
 *     extracts at all. Default 50.
 * @param maxAttachmentBytes the maximum size, in bytes, streamed into a single attachment's temp
 *     file before aborting. Default 50 MiB.
 * @param maxMessageBytes the maximum size, in bytes, of the message file itself that either reader
 *     is allowed to parse at all. Default 100 MiB.
 */
@ConfigurationProperties(prefix = "opaa.indexing.mail")
public record MailProperties(
    int maxAttachmentDepth,
    int maxAttachmentsPerMessage,
    long maxAttachmentBytes,
    long maxMessageBytes) {

  public MailProperties {
    if (maxAttachmentDepth <= 0) {
      maxAttachmentDepth = 5;
    }
    if (maxAttachmentsPerMessage <= 0) {
      maxAttachmentsPerMessage = 50;
    }
    if (maxAttachmentBytes <= 0) {
      maxAttachmentBytes = 52_428_800L; // 50 MiB
    }
    if (maxMessageBytes <= 0) {
      maxMessageBytes = 104_857_600L; // 100 MiB
    }
  }
}
