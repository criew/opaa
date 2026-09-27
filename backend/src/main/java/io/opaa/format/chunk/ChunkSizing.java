package io.opaa.format.chunk;

/**
 * The size and overlap {@link ChunkingService} cuts with, in tokens of the splitter's encoding. The
 * values come from the application's configuration, which implements this interface; {@code
 * chunkOverlap} is smaller than {@code chunkSize} and never negative.
 */
public interface ChunkSizing {

  int chunkSize();

  int chunkOverlap();
}
