package io.opaa.indexing;

/**
 * The narrow slice of {@link IndexingRunProgress} a source-agnostic collaborator (e.g. {@code
 * io.opaa.indexing.source.attachment.AttachmentAccess}) needs to count an indexed attachment -
 * split out so a caller with no job/run of its own (a single document upload) can supply a
 * lightweight implementation instead of a full, job-bound {@link IndexingRunProgress}.
 */
public interface AttachmentProgressSink {

  void recordDocumentIndexed();
}
