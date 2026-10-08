package io.opaa.indexing.chunk;

/**
 * One chunk alone exceeds the input budget of the embedding step, so no batching can send it;
 * nothing of the call was embedded or written. Names the chunk by its Fundort and position, either
 * of which may be absent.
 */
public class ChunkNotEmbeddableException extends RuntimeException {

  private final String location;
  private final Integer chunkIndex;

  public ChunkNotEmbeddableException(String location, Integer chunkIndex, Throwable cause) {
    super(
        "Chunk "
            + chunkIndex
            + " ("
            + location
            + ") exceeds the input token budget of the embedding step",
        cause);
    this.location = location;
    this.chunkIndex = chunkIndex;
  }

  /** The chunk's Fundort, or {@code null} when it carries none. */
  public String location() {
    return location;
  }

  /** The chunk's 0-based position in its document, or {@code null} when it carries none. */
  public Integer chunkIndex() {
    return chunkIndex;
  }
}
