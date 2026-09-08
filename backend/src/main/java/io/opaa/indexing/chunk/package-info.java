/**
 * What a chunk looks like and where it lands: cutting text into chunks ({@link
 * io.opaa.indexing.chunk.ChunkingService}), the context prefix and Fundort every chunk carries, the
 * metadata keys a chunk is allowed to carry, and the two stores a chunk is written to - the
 * pgvector {@code vector_store} through {@link io.opaa.indexing.chunk.VectorChunkStore} and the
 * lexical {@code chunk_full_text} through {@link io.opaa.indexing.chunk.FullTextChunkStore}, which
 * is reached only from there so a full-text row can never outlive its vector chunk.
 *
 * <p>{@link io.opaa.indexing.chunk.MarkdownHeading} lives here rather than with the Markdown format
 * because both sides of it are here-and-there: the format cuts on a heading, {@link
 * io.opaa.indexing.chunk.ChunkLocationResolver} finds the same heading again inside stored chunk
 * text. One reading, in the package the stored chunk belongs to.
 *
 * <p>Holds no document, run or job state and calls back into none of those packages: {@code
 * document}, {@code maintenance} and {@code format} use this package, not the reverse.
 */
package io.opaa.indexing.chunk;
