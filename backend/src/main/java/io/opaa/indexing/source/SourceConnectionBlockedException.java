package io.opaa.indexing.source;

import java.util.Objects;

/**
 * The port's refusal to hand out target and secret of a library (ADR-0041, Entscheidung 3): its
 * {@link SourceBlock}, whose notice is the message. A run ends with the message before its body
 * starts.
 */
public class SourceConnectionBlockedException extends RuntimeException {

  private final SourceBlock block;

  public SourceConnectionBlockedException(SourceBlock block) {
    super(Objects.requireNonNull(block, "block").notice());
    this.block = block;
  }

  public SourceBlock block() {
    return block;
  }
}
