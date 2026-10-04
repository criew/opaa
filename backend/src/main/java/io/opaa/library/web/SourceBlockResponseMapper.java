package io.opaa.library.web;

import io.opaa.api.dto.SourceBlock;
import io.opaa.api.dto.SourceBlockReason;

/** Maps the block of a library's source onto the generated {@code SourceBlock}, notice included. */
final class SourceBlockResponseMapper {

  private SourceBlockResponseMapper() {}

  static SourceBlock toResponse(io.opaa.indexing.source.SourceBlock block) {
    if (block == null) {
      return null;
    }
    return new SourceBlock(SourceBlockReason.valueOf(block.reason().name()), block.responsible())
        .notice(block.notice());
  }
}
