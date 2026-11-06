package io.opaa.indexing.job;

/**
 * The narrow slice of {@link IndexingRunEventRecorder} a source-agnostic collaborator (e.g. {@code
 * io.opaa.indexing.attachment.AttachmentAccess}) needs to protocol an item - split out so a caller
 * with no job/run of its own (a single document upload) can supply a lightweight implementation
 * instead of a full, job-bound {@link IndexingRunEventRecorder}.
 */
public interface IndexingEventSink {

  void record(IndexingEventCategory category, String message, String reference);

  /**
   * The text recognition page budget of the run this protocol belongs to, or {@code null} for a
   * caller without a run (an upload): such a caller sends a scan only while this instance has a
   * free slot and otherwise leaves it to the background catch-up.
   */
  default TextRecognitionRunBudget textRecognitionBudget() {
    return null;
  }

  /**
   * Whether a scan waits for a busy text recognition slot of this instance; by default a run waits
   * and a caller without one does not. A bounded pass under a cluster lock does not wait.
   */
  default boolean waitsForTextRecognitionSlot() {
    return textRecognitionBudget() != null;
  }
}
