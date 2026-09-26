/**
 * How a document is taken in: the one ingestion path every source and every upload goes through,
 * {@link io.opaa.indexing.document.DocumentIngestService#ingest} on a {@link
 * io.opaa.indexing.document.DocumentIngest} - parse, chunk, store, mark.
 *
 * <p>The {@code Document} row itself, with its provenance, checksum and status, belongs to the
 * holdings in {@code io.opaa.knowledge}. Uses {@code format} for the format handling, {@code chunk}
 * for cutting and storing, {@code metadata} for the schema fields and {@code attachment} for
 * discovered attachments. Of {@code job} it knows only the protocol sink it reports each document's
 * outcome to; the run, its schedule and its progress stay there. {@code maintenance} depends on
 * this package, never the other way round.
 */
package io.opaa.indexing.document;
