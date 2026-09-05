package io.opaa.indexing.source.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limits {@link AttachmentIndexer} applies to every connector's attachments (ADR-0022,
 * Entscheidung 6): the nesting depth for all, and the count and download size for a connector
 * without numbers of its own ({@link #limits()}). A connector that bounds its attachments itself
 * (RSS per entry, Mail per message) hands its own {@link AttachmentLimits} in instead.
 *
 * @param maxDepth how many levels of nested attachment-in-attachment recursion {@link
 *     AttachmentIndexer} descends into before it stops and skips the remainder. Default 5.
 * @param maxPerParent how many attachments of one parent are indexed. Default 10.
 * @param maxSizeBytes the most bytes a single attachment download may transfer. Default 20 MiB.
 */
@ConfigurationProperties(prefix = "opaa.indexing.attachments")
public record AttachmentProperties(int maxDepth, int maxPerParent, long maxSizeBytes) {

  public AttachmentProperties {
    if (maxDepth <= 0) {
      maxDepth = 5;
    }
    if (maxPerParent <= 0) {
      maxPerParent = 10;
    }
    if (maxSizeBytes <= 0) {
      maxSizeBytes = 20_971_520L;
    }
  }

  /** The count and size limits for a connector without numbers of its own. */
  public AttachmentLimits limits() {
    return new AttachmentLimits(maxPerParent, maxSizeBytes);
  }
}
