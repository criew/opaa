/**
 * Everything that works over the existing stock instead of over the inflow: re-indexing after a
 * pipeline version bump, re-stamping context prefixes, the deterministic core-metadata backfill,
 * removing documents a source no longer contains, and the read-only audits over what is already
 * indexed (low chunk counts, full-text index fill state).
 *
 * <p>The resumable passes share the shape {@link io.opaa.indexing.maintenance.DocumentBatchLoop}
 * describes: the remaining work is re-derived on every call, a document that cannot be advanced is
 * scanned past by offset rather than falsified, and pausing is not calling again.
 *
 * <p>Uses {@code document} and {@code chunk} to read and rewrite, {@code format} and {@code
 * metadata} to redo what an ingest did, and the protocol of {@code job} to report what it removed.
 * Its callers sit outside this package: the administration endpoints, the search status page, the
 * run frame in {@code source} for reconciliation by absence, and {@code
 * metadata.LibraryMetadataFieldService} for its own field remapping - so {@code metadata} and this
 * package depend on each other in code, in both directions.
 */
package io.opaa.indexing.maintenance;
