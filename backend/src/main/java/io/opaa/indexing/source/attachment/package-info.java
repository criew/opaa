/**
 * The shared attachment path every connector's own package can build on (ADR-0022, Entscheidung 8):
 * {@link io.opaa.indexing.source.attachment.AttachmentIndexer} turns a source-agnostic list of
 * {@link io.opaa.indexing.source.attachment.AttachmentSource} into their own {@code Document} rows,
 * given only an {@link io.opaa.indexing.source.attachment.AttachmentAccess} - no dependency on any
 * single source's own package. A connector that reconciles by absence uses {@code
 * io.opaa.indexing.source.ReconcilingAttachmentAccess}; RSS supplies its own {@code
 * RssFeedRunContext}, an upload or a pipeline re-index a run-less {@code
 * StandaloneAttachmentAccess}. {@link io.opaa.indexing.source.attachment.AttachmentProfile} decides
 * which links on an RSS detail page are attachments.
 */
package io.opaa.indexing.source.attachment;
