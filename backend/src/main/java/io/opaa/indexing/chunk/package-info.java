/**
 * What a chunk looks like and where it lands: cutting text into chunks ({@link
 * io.opaa.indexing.chunk.ChunkingService}), the context prefix and Fundort every chunk carries, and
 * the two stores a chunk is written to - the pgvector {@code vector_store} through {@link
 * io.opaa.indexing.chunk.VectorChunkStore} and the lexical {@code chunk_full_text} through {@link
 * io.opaa.indexing.chunk.FullTextChunkStore}, which is reached only from there so a full-text row
 * can never outlive its vector chunk.
 *
 * <p>Holds no document, run or job state and calls back into none of those packages: {@code
 * document} and {@code maintenance} use this package, not the reverse.
 */
package io.opaa.indexing.chunk;
