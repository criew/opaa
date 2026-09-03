/**
 * The shared attachment path every connector's own package can build on (ADR-0022, Entscheidung 8):
 * {@link io.opaa.indexing.source.attachment.AttachmentIndexer} turns a source-agnostic list of
 * {@link io.opaa.indexing.source.attachment.AttachmentSource} into their own {@code Document} rows,
 * given only an {@link io.opaa.indexing.source.attachment.AttachmentAccess} - no dependency on any
 * single source's own package. {@code source.rss} is the first consumer ({@link
 * io.opaa.indexing.source.attachment.AttachmentProfile} decides which links on an RSS detail page
 * are attachments, {@code RssFeedRunContext} implements {@code AttachmentAccess} directly); Mail
 * (#1183) and Confluence (#1137) are meant to follow the same shape.
 */
package io.opaa.indexing.source.attachment;
