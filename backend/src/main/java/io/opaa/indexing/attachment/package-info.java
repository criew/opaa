/**
 * What a caller hands the shared attachment path (ADR-0022, Entscheidung 8): a source-agnostic list
 * of {@link io.opaa.indexing.attachment.AttachmentSource}, an {@link
 * io.opaa.indexing.attachment.AttachmentAccess} to the caller's run, the {@link
 * io.opaa.indexing.attachment.AttachmentLimits}, and the {@link
 * io.opaa.indexing.attachment.AttachmentOutcome} counted per attachment. The path itself, {@code
 * AttachmentIndexer}, lies in {@code document}, since every attachment is taken in through the one
 * ingestion path. A connector that reconciles by absence uses {@code
 * io.opaa.indexing.source.ReconcilingAttachmentAccess}; RSS supplies its own {@code
 * RssFeedRunContext}, an upload or a pipeline re-index a run-less {@code
 * StandaloneAttachmentAccess}. {@link io.opaa.indexing.attachment.AttachmentProfile} decides which
 * links on an RSS detail page are attachments.
 *
 * <p>Knows of the pipeline only the protocol sink of {@code job}; {@code document} and everything
 * above use this package, never the other way round.
 */
package io.opaa.indexing.attachment;
