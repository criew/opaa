/**
 * The generic cut of text into chunks and the Fundort a chunk carries: {@link
 * io.opaa.format.chunk.ChunkingService} cuts by tokens with overlap and stamps {@link
 * io.opaa.format.chunk.ChunkMetadataKeys#LOCATION_METADATA_KEY}, which every format writes and
 * search reads back. Page breaks from {@link io.opaa.format.chunk.PageMarkingContentHandler} and
 * headings in the one reading of {@link io.opaa.format.chunk.MarkdownHeading} are the two signals
 * of a Fundort; the Markdown format cuts on the same heading reading.
 */
package io.opaa.format.chunk;
