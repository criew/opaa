package io.opaa.format.config;

import io.opaa.format.shared.HeadingSectionSplitter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Target size of the structure-aware cut ({@code OPAA_INDEXING_CHUNK_TARGET_CHARACTERS}), in
 * characters - shared by every format on {@link HeadingSectionSplitter}. Independent of {@code
 * opaa.indexing.chunk-size}, which counts tokens and only governs the Tika fallback and plain
 * texts. A changed value applies to documents indexed afterwards; existing chunks record the value
 * they were cut with and are re-cut by the pipeline re-index.
 *
 * @param chunkTargetCharacters default {@value
 *     HeadingSectionSplitter#DEFAULT_SOFT_CHUNK_CHAR_LIMIT}; 0 or a negative value means the
 *     default. Valid range: {@value HeadingSectionSplitter#MIN_SOFT_CHUNK_CHAR_LIMIT}–{@value
 *     HeadingSectionSplitter#HARD_CHUNK_CHAR_LIMIT}.
 */
@ConfigurationProperties(prefix = "opaa.indexing")
public record ChunkTargetProperties(int chunkTargetCharacters) {

  public ChunkTargetProperties {
    if (chunkTargetCharacters <= 0) {
      chunkTargetCharacters = HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT;
    }
    if (chunkTargetCharacters < HeadingSectionSplitter.MIN_SOFT_CHUNK_CHAR_LIMIT
        || chunkTargetCharacters > HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT) {
      throw new IllegalArgumentException(
          "chunkTargetCharacters must be between "
              + HeadingSectionSplitter.MIN_SOFT_CHUNK_CHAR_LIMIT
              + " and "
              + HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT
              + ", got "
              + chunkTargetCharacters);
    }
  }
}
