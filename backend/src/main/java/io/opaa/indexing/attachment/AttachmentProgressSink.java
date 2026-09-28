package io.opaa.indexing.attachment;

/**
 * The narrow slice of {@code IndexingRunProgress} a source-agnostic collaborator (e.g. {@code
 * io.opaa.indexing.attachment.AttachmentAccess}) needs to count an attachment's outcome - split out
 * so a caller with no job/run of its own (a single document upload) can supply a lightweight
 * implementation instead of a full, job-bound {@code IndexingRunProgress}.
 */
public interface AttachmentProgressSink {

  /** Counts one attachment by its outcome; only {@code PROCESSED} adds a document to the run. */
  void recordAttachment(AttachmentOutcome outcome);
}
