/**
 * Where a chunk lands and what the index adds to it: the context prefix of its embedding ({@link
 * io.opaa.metadata.ChunkContextPrefix}), the source keys every chunk of a document carries ({@link
 * io.opaa.indexing.chunk.SourceChunkMetadataKeys}), and the two stores a chunk is written to - the
 * pgvector {@code vector_store} through {@link io.opaa.indexing.chunk.VectorChunkStore} and the
 * lexical {@code chunk_full_text} through {@link io.opaa.indexing.chunk.FullTextChunkStore}, which
 * is reached only from there so a full-text row can never outlive its vector chunk. The cut itself
 * and the Fundort belong to {@code io.opaa.format.chunk}.
 *
 * <p>Holds no document, run or job state and calls back into none of those packages: {@code
 * document} and {@code maintenance} use this package, not the reverse.
 */
package io.opaa.indexing.chunk;
