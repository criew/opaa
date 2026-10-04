package io.opaa.indexing.chunk;

/** Chunks were refused because their library is gone or being erased; nothing was written. */
public class ChunkTargetGoneException extends RuntimeException {

  public ChunkTargetGoneException() {
    super("The library of these chunks is gone or being erased");
  }
}
