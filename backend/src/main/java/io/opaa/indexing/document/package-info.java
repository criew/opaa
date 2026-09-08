/**
 * What a document is and how one is taken in: the {@link io.opaa.indexing.document.Document} row
 * with its provenance, checksum and status, and the one ingestion path every source and every
 * upload goes through, {@link io.opaa.indexing.document.DocumentIngestService#ingest} on a {@link
 * io.opaa.indexing.document.DocumentIngest} - parse, chunk, store, mark.
 *
 * <p>Uses {@code format} for the format handling, {@code chunk} for cutting and storing, {@code
 * metadata} for the schema fields and {@code source.attachment} for discovered attachments. Of
 * {@code job} it knows only the protocol sink it reports each document's outcome to; the run, its
 * schedule and its progress stay there. {@code maintenance} depends on this package, never the
 * other way round.
 */
package io.opaa.indexing.document;
