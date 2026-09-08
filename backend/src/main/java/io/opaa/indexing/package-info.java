/**
 * Document indexing for OPAA. The work itself lives in the subpackages - {@link
 * io.opaa.indexing.document} (what a document is and how one is taken in), {@link
 * io.opaa.indexing.chunk} (how a chunk is cut and where it lands), {@link io.opaa.indexing.job}
 * (run, schedule, protocol), {@link io.opaa.indexing.maintenance} (passes over the existing stock),
 * {@link io.opaa.indexing.pipeline} (per-format handling), {@link io.opaa.indexing.source} (the
 * connectors) and {@link io.opaa.indexing.metadata} (the schema fields).
 *
 * <p>What stays here is what all of them share: the wiring ({@link
 * io.opaa.indexing.IndexingConfiguration}), the bound properties, and the admission decision {@link
 * io.opaa.indexing.SupportedDocumentFormats} makes over a file's content. The configuration knows
 * every subpackage because it wires them; the subpackages know only the properties and the
 * admission decision from here, never each other through this package.
 */
package io.opaa.indexing;
