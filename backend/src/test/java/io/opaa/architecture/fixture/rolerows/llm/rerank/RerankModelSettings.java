package io.opaa.architecture.fixture.rolerows.llm.rerank;

import io.opaa.architecture.fixture.rolerows.llm.OptionalRoleSettings;
import java.time.Instant;

/** Calls the row's own constructor and writer, as it may. */
public class RerankModelSettings extends OptionalRoleSettings {

  protected RerankModelSettings() {}

  public RerankModelSettings(Instant now) {
    super(now);
    replaceOwn("x");
  }

  public void replaceOwn(String language) {}
}
