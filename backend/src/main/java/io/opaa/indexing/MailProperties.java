package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link MailDocumentPipeline} (#1060) applies to an EML/MSG message - own
 * property block, own binding prefix, mirroring {@link TabularProperties}'s own reasoning: these
 * are memory-safety ceilings, not part of the Zuschnitt itself
 * (docs/features/ingestion-pipelines.md, Querschnittsregel c).
 *
 * <p>An attachment can itself be an EML or MSG carrying further attachments - the actual
 * justification for routing an attachment back through {@link DocumentPipelineRegistry} instead of
 * flattening it. {@link #maxAttachmentDepth} bounds that recursion; the other three bound a single
 * message's own size and attachment fan-out, the same style of guard {@code
 * IndexingProperties.Rss}'s per-entry attachment limits already are.
 *
 * <p><b>{@link #maxAttachmentBytes} bounds disk and downstream processing, not the in-memory parse
 * itself (#1101 review, finding 3).</b> Both readers build their whole DOM (mime4j's {@code
 * BasicBodyFactory}, POI's {@link org.apache.poi.hsmf.MAPIMessage}) before this pipeline's own code
 * ever runs - every part of the message, including every attachment's bytes, is already held in
 * memory by the time an attachment is even identified as one. {@link #maxAttachmentBytes} only
 * bounds what is written to a temp file (and, for EML, streamed while writing - see {@link
 * MailAttachmentIo}) and handed to a sub-pipeline afterwards; it cannot retroactively bound memory
 * a parser already committed to. {@link #maxMessageBytes} is the actual memory guard: it is checked
 * against the message file's own size before either reader ever runs, so a pathologically large
 * message is rejected before its DOM is built at all.
 *
 * @param maxAttachmentDepth how many levels of nested mail-in-mail attachments {@link
 *     MailDocumentPipeline} descends into before it stops recursing and skips the remainder - a
 *     message attaching itself (directly or through a cycle of several messages) would otherwise
 *     recurse without bound. Default 5: deep enough for a forwarded chain of a few messages,
 *     shallow enough to bound worst-case recursion.
 * @param maxAttachmentsPerMessage the maximum number of attachments a single message's own reader
 *     ({@link EmlReader}/{@link MsgReader}) extracts at all - enforced in the extraction loop
 *     itself, not afterwards, so an attachment beyond this count never gets its own temp file
 *     created in the first place. Default 50.
 * @param maxAttachmentBytes the maximum size, in bytes, {@link EmlReader} streams into a single
 *     attachment's temp file before aborting - see this record's own Javadoc for why this bounds
 *     disk, not the parser's prior in-memory footprint. Default 50 MiB, generous for a real
 *     Bescheid or Anlage while bounding a hostile message's worst case on disk.
 * @param maxMessageBytes the maximum size, in bytes, of the message file itself ({@code
 *     .eml}/{@code .msg}) that either reader is allowed to parse at all - checked by {@link
 *     MailDocumentPipeline} against the file's own size before {@link EmlReader#read}/{@link
 *     MsgReader#read} ever runs, the actual bound on the in-memory DOM both readers build. {@code
 *     FileProcessingService#processFile} enforces no per-file size limit of its own (only the
 *     library's total storage quota), so this is the only ceiling a single oversized message file
 *     meets before being handed to a full-message in-memory parse. Default 100 MiB.
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
