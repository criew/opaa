/**
 * Document indexing for OPAA. The work itself lives in the subpackages - {@link
 * io.opaa.indexing.document} (what a document is and how one is taken in), {@link
 * io.opaa.indexing.chunk} (where a chunk lands), {@link io.opaa.indexing.job} (the run's row and
 * protocol), {@link io.opaa.indexing.maintenance} (passes over the existing stock), {@link
 * io.opaa.indexing.attachment} (what a caller hands the attachment path), {@link
 * io.opaa.indexing.source} (the connector contract, the run frame and its triggers, and the
 * connectors) and {@link io.opaa.metadata} (the schema fields). Parsing and cutting per format live
 * below, in {@link io.opaa.format}.
 *
 * <p>The core subpackages depend on each other in one direction, lowest first: {@code chunk} and
 * {@code job}, {@code attachment}, {@code document}, {@code source}, {@code maintenance}. What
 * stays here sits above all of them: the core wiring ({@link
 * io.opaa.indexing.IndexingConfiguration}) and the bound properties. A subpackage never refers to
 * this package; what it needs of a property it declares as an interface the property record
 * implements ({@code EmbeddingBatching}, {@code RunRecoverySettings}, {@code
 * FilesystemAllowlistSettings}). Each connector wires itself in its own package; the core
 * configuration knows none of them. The admission decision over a file's content is {@link
 * io.opaa.format.SupportedDocumentFormats}, next to the formats it admits for; {@code
 * FormatConfiguration} registers the formats.
 */
package io.opaa.indexing;
