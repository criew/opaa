package io.opaa.indexing.attachment;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limits {@code AttachmentIndexer} applies to every connector's attachments (ADR-0022,
 * Entscheidung 6): the nesting depth and the budget per root document for all, and the count and
 * download size for a connector without numbers of its own ({@link #limits()}). A connector that
 * bounds its attachments itself (RSS per entry, Mail per message) hands its own {@link
 * AttachmentLimits} in instead.
 *
 * @param maxDepth how many levels of nested attachment-in-attachment recursion {@code
 *     AttachmentIndexer} descends into before it stops and skips the remainder. Default 5.
 * @param maxPerParent how many attachments of one parent are indexed. Default 10.
 * @param maxSizeBytes the most bytes a single attachment download may transfer. Default 20 MiB.
 * @param maxPerRoot how many attachments one document indexes over all levels of nesting together,
 *     its attachments, theirs and so on; the rest is skipped. Default 500.
 * @param maxBytesPerRoot the most bytes the attachments of one document may have over all levels of
 *     nesting together; an attachment that would pass it is skipped. Default 1 GiB.
 */
@PerInstance("bounds of the attachment extraction of this process")
@NoDataImpact("attachment bounds")
@ConfigurationProperties(prefix = "opaa.indexing.attachments")
public record AttachmentProperties(
    int maxDepth, int maxPerParent, long maxSizeBytes, int maxPerRoot, long maxBytesPerRoot) {

  public AttachmentProperties {
    maxDepth = positiveOr(maxDepth, 5);
    maxPerParent = positiveOr(maxPerParent, 10);
    maxSizeBytes = positiveOr(maxSizeBytes, 20_971_520L);
    maxPerRoot = positiveOr(maxPerRoot, 500);
    maxBytesPerRoot = positiveOr(maxBytesPerRoot, 1_073_741_824L); // 1 GiB
  }

  /** The count and size limits for a connector without numbers of its own. */
  public AttachmentLimits limits() {
    return new AttachmentLimits(maxPerParent, maxSizeBytes);
  }
}
