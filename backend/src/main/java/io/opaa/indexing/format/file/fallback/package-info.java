/**
 * The Tika fallback format (docs/features/ingestion-pipelines.md): claims no extension and handles
 * every admitted document no other file format claimed. Registering a new format must therefore
 * never change what this one produces for a format it does not claim.
 *
 * <p>{@link io.opaa.indexing.format.ChunkFormatMetadata} reaches back into this package for {@link
 * io.opaa.indexing.format.file.fallback.TikaFallbackFormat#ID}: every chunk written before the
 * format abstraction existed came from exactly this reader, so that is the id it is attributed to.
 * The two packages therefore depend on each other.
 */
package io.opaa.indexing.format.file.fallback;
