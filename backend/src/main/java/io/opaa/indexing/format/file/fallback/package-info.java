/**
 * The Tika fallback format (docs/features/ingestion-pipelines.md): claims no extension for routing
 * and handles every admitted document no other file format claimed. Registering a new format must
 * therefore never change what this one produces for a format it does not claim.
 *
 * <p>It does admit two extensions of its own ({@code .txt}, {@code .doc}) - the named decision
 * "accepted, without a format of its own", which is the only reason they are accepted at all.
 *
 * <p>The dependency on {@code io.opaa.indexing.format} runs one way only: {@link
 * io.opaa.indexing.format.file.fallback.TikaFallbackFormat#ID} points at {@link
 * io.opaa.indexing.format.ChunkFormatMetadata#LEGACY_PIPELINE_ID}, where the persisted value is
 * declared, because every chunk written before the format abstraction existed came from exactly
 * this reader.
 */
package io.opaa.indexing.format.file.fallback;
