package io.opaa.architecture.fixture.security.rows;

import org.hibernate.annotations.DynamicUpdate;

/** Security state written column by column: only a save of a detached instance is stale. */
@DynamicUpdate
public class DynamicGroup {

  private boolean deactivated;

  public void deactivate() {
    this.deactivated = true;
  }
}
