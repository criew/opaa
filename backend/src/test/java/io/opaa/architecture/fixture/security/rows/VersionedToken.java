package io.opaa.architecture.fixture.security.rows;

import jakarta.persistence.Version;
import java.time.Instant;

/** Security state an optimistic lock guards. */
public class VersionedToken {

  private Instant revokedAt;

  @Version private long version;

  public void revoke(Instant at) {
    this.revokedAt = at;
  }
}
