package io.opaa.indexing.pipeline.mail;

/**
 * Caps how many attachments a single {@link EmlReader}/{@link MsgReader} extraction pass creates a
 * temp file for - enforced in the extraction loop itself (#1101 review, finding 3c), not
 * afterwards: an earlier version let both readers create a temp file for every attachment a message
 * carried and only discarded the surplus in {@link MailDocumentPipeline}, so a message with
 * thousands of attachments still paid for every one of them before any limit applied.
 */
final class AttachmentBudget {

  private final int max;
  private int used;

  AttachmentBudget(int max) {
    this.max = max;
  }

  /** Whether the budget still has room for one more attachment. */
  boolean hasCapacity() {
    return used < max;
  }

  /** Records that one more attachment was taken from the budget. */
  void reserve() {
    used++;
  }

  /** Whether {@link #hasCapacity()} ever returned {@code false} during this pass. */
  boolean exhausted() {
    return used >= max;
  }
}
