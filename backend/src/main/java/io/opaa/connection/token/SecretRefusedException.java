package io.opaa.connection.token;

import io.opaa.indexing.source.SourceBlock.Reason;
import java.util.Objects;

/**
 * {@link ConnectionSecrets} hands out no secret for an owner now, for {@link #reason()}. The caller
 * turns it into the block with its notice, which only {@code SourceBlocks} words.
 */
public class SecretRefusedException extends RuntimeException {

  private final transient Reason reason;

  public SecretRefusedException(Reason reason) {
    super("No secret handed out: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
