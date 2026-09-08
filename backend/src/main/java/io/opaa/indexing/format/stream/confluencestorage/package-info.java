/**
 * The Confluence storage format (ADR-0023; docs/features/ingestion-pipelines.md, Teil 3, Punkt 6):
 * a page body in XHTML with {@code ac:}/{@code ri:} macro elements, identical for Cloud and Data
 * Center, cut into heading sections.
 *
 * <p>Reached only through {@code DocumentIngest.pipelineId} from the Confluence connector. Space
 * key and hierarchy path are not in the body; the format declares them as passthrough and the
 * ingest puts them on the chunks.
 */
package io.opaa.indexing.format.stream.confluencestorage;
