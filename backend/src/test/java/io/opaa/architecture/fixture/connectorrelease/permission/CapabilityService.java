package io.opaa.architecture.fixture.connectorrelease.permission;

import io.opaa.architecture.fixture.connectorrelease.api.types.Capability;
import java.util.Set;

public abstract class CapabilityService {

  public abstract boolean hasCapability(Capability capability);

  public abstract boolean hasCapability(Capability capability, String scope);

  public abstract Set<String> scopesOf(Capability capability);

  boolean releasedForAll() {
    return hasCapability(Capability.CREATE_CONNECTOR_LIBRARY, "TYPE:ALL");
  }
}
