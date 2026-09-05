package io.opaa.indexing.pipeline.mail;

/**
 * Caps how many attachments a single {@link EmlReader}/{@link MsgReader} extraction pass creates a
 * temp file for. Enforced inside the extraction loop, never afterwards: a message with thousands of
 * attachments must not pay for a temp file per attachment before the limit applies.
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
