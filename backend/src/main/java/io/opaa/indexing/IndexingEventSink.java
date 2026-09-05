package io.opaa.indexing;

/**
 * The narrow slice of {@link IndexingRunEventRecorder} a source-agnostic collaborator (e.g. {@code
 * io.opaa.indexing.source.attachment.AttachmentAccess}) needs to protocol an item - split out so a
 * caller with no job/run of its own (a single document upload) can supply a lightweight
 * implementation instead of a full, job-bound {@link IndexingRunEventRecorder}.
 */
public interface IndexingEventSink {

  void record(IndexingEventCategory category, String message, String reference);
}
