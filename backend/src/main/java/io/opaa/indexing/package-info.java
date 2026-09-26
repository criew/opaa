/**
 * Document indexing for OPAA. The work itself lives in the subpackages - {@link
 * io.opaa.indexing.document} (what a document is and how one is taken in), {@link
 * io.opaa.indexing.chunk} (how a chunk is cut and where it lands), {@link io.opaa.indexing.job}
 * (run, schedule, protocol), {@link io.opaa.indexing.maintenance} (passes over the existing stock),
 * {@link io.opaa.indexing.format} (per-format handling), {@link io.opaa.indexing.attachment}
 * (attachments as their own documents), {@link io.opaa.indexing.source} (the run frame and the
 * connectors) and {@link io.opaa.indexing.metadata} (the schema fields).
 *
 * <p>What stays here is what all of them share: the core wiring ({@link
 * io.opaa.indexing.IndexingConfiguration}) and the bound properties. Each connector wires itself in
 * its own package; the core configuration knows none of them. The admission decision over a file's
 * content moved to {@link io.opaa.indexing.format.SupportedDocumentFormats}, next to the formats it
 * admits for. The configuration knows every core subpackage because it wires them; the subpackages
 * know only the properties from here, never each other through this package.
 */
package io.opaa.indexing;
