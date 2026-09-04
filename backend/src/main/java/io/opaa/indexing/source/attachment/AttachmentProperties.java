package io.opaa.indexing.source.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The general attachment-nesting depth limit {@link AttachmentIndexer} enforces (ADR-0022,
 * Entscheidung 6) - a single value shared by every connector's attachment chain (Mail-in-Mail,
 * Feed-Anlage), since the recursion mechanism is the same regardless of which format or connector
 * discovered the nested attachment. Deliberately its own property block, not a field on {@code
 * IndexingProperties}: the remaining, source-specific {@link AttachmentDownloadLimits} fields
 * (count per parent, size, request delay, user agent) stay with their own connector because they
 * genuinely differ between a local file and a download.
 *
 * @param maxDepth how many levels of nested attachment-in-attachment recursion {@link
 *     AttachmentIndexer} descends into before it stops and skips the remainder. Default 5.
 */
@ConfigurationProperties(prefix = "opaa.indexing.attachments")
public record AttachmentProperties(int maxDepth) {

  public AttachmentProperties {
    if (maxDepth <= 0) {
      maxDepth = 5;
    }
  }
}
