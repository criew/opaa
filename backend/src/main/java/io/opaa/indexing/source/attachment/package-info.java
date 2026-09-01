/**
 * RSS entry attachment discovery and indexing, consumed by {@code source.rss} (ADR-0017): {@link
 * io.opaa.indexing.source.attachment.AttachmentProfile} decides which links on a detail page are
 * attachments, {@link io.opaa.indexing.source.attachment.AttachmentIndexer} downloads and indexes
 * them.
 */
package io.opaa.indexing.source.attachment;
