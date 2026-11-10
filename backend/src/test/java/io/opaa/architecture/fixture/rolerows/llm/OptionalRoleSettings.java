package io.opaa.architecture.fixture.rolerows.llm;

import java.time.Instant;

public abstract class OptionalRoleSettings {

  protected OptionalRoleSettings() {}

  protected OptionalRoleSettings(Instant now) {}

  public void replaceEndpoint(String baseUrl) {}
}
