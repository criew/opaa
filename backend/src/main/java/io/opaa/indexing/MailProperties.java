package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DoS-hardening limits {@link MailDocumentPipeline} (#1060) applies to an EML/MSG message's
 * attachments - own property block, own binding prefix, mirroring {@link TabularProperties}'s own
 * reasoning: these are memory-safety ceilings, not part of the Zuschnitt itself
 * (docs/features/ingestion-pipelines.md, Querschnittsregel c).
 *
 * <p>An attachment can itself be an EML or MSG carrying further attachments - the actual
 * justification for routing an attachment back through {@link DocumentPipelineRegistry} instead of
 * flattening it. {@link #maxAttachmentDepth} bounds that recursion; the other two bound a single
 * message's own attachment fan-out, the same style of guard {@code IndexingProperties.Rss}'s
 * per-entry attachment limits already are.
 *
 * @param maxAttachmentDepth how many levels of nested mail-in-mail attachments {@link
 *     MailDocumentPipeline} descends into before it stops recursing and skips the remainder - a
 *     message attaching itself (directly or through a cycle of several messages) would otherwise
 *     recurse without bound. Default 5: deep enough for a forwarded chain of a few messages,
 *     shallow enough to bound worst-case recursion.
 * @param maxAttachmentsPerMessage the maximum number of attachments read from a single message - an
 *     attachment beyond this count is skipped and logged, the message's own body is unaffected.
 *     Default 50.
 * @param maxAttachmentBytes the maximum size, in bytes, of a single attachment's extracted content
 *     - enforced while copying it to a temporary file, not after the fact, so a pathologically
 *     large attachment never has to be held in memory or on disk in full before being rejected.
 *     Default 50 MiB, generous for a real Bescheid or Anlage while bounding a hostile message's
 *     worst case.
 */
@ConfigurationProperties(prefix = "opaa.indexing.mail")
public record MailProperties(
    int maxAttachmentDepth, int maxAttachmentsPerMessage, long maxAttachmentBytes) {

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
  }
}
