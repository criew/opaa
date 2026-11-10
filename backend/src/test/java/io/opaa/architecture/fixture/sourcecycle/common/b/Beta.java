package io.opaa.architecture.fixture.sourcecycle.common.b;

import static io.opaa.architecture.fixture.sourcecycle.common.a.Alpha.LIMIT;

/** Reads the limit of its sibling package; the compiler inlines the constant. */
public class Beta {

  public int limit() {
    return LIMIT;
  }
}
